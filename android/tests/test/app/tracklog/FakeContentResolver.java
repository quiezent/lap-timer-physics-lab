package app.tracklog;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fake MediaStore-backed ContentResolver: a table of rows with an is_pending flag.
 * Assumed semantics: insert() creates a row with the given values and returns content://media/external/downloads/ID;
 * openOutputStream() on an unknown/deleted row throws FileNotFoundException; data becomes the row's content on close();
 * update() merges is_pending; delete() removes the row. No renaming on name clashes, no permission checks.
 * Injectable: insertDelayMs / writeDelayMs (sleep), failInsert (insert returns null), failWrite (first write throws),
 * failClose (close throws after the data was stored).
 */
final class FakeContentResolver extends ContentResolver {
    static final class Row {
        final long id; final String name, mime, path;
        volatile int pending; volatile byte[] data;
        Row(long id, ContentValues v) {
            this.id = id;
            name = v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            mime = v.getAsString(MediaStore.MediaColumns.MIME_TYPE);
            path = v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
            Integer p = v.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
            pending = p == null ? 0 : p;
        }
    }

    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    volatile long insertDelayMs, writeDelayMs;
    volatile boolean failInsert, failWrite, failClose;
    volatile int insertCalls, openCalls, updateCalls, deleteCalls;

    synchronized List<Row> rows() { return new ArrayList<>(rows.values()); }
    int pendingCount() { int n = 0; for (Row r : rows()) if (r.pending != 0) n++; return n; }
    int visibleCount() { int n = 0; for (Row r : rows()) if (r.pending == 0) n++; return n; }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private synchronized Row find(Uri u) {
        try { return rows.get(Long.parseLong(u.getLastPathSegment())); } catch (RuntimeException e) { return null; }
    }

    @Override public Uri insert(Uri url, ContentValues values) {
        insertCalls++;
        sleep(insertDelayMs);
        if (failInsert) return null;
        long id = ids.incrementAndGet();
        synchronized (this) { rows.put(id, new Row(id, values)); }
        return Uri.parse(url + "/" + id);
    }

    @Override public OutputStream openOutputStream(Uri uri) throws FileNotFoundException {
        openCalls++;
        final Row row = find(uri);
        if (row == null) throw new FileNotFoundException("no such row: " + uri);
        return new OutputStream() {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            private boolean closed;
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (closed) throw new IOException("stream closed");
                sleep(writeDelayMs);
                if (failWrite) throw new IOException("No space left on device (injected)");
                buf.write(b, off, len);
            }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                row.data = buf.toByteArray();
                if (failClose) throw new IOException("close failed (injected)");
            }
        };
    }

    @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
        updateCalls++;
        Row r = find(uri);
        if (r == null) return 0;
        Integer p = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
        if (p != null) r.pending = p;
        return 1;
    }

    @Override public int delete(Uri url, String where, String[] args) {
        deleteCalls++;
        Row r = find(url);
        if (r == null) return 0;
        synchronized (this) { rows.remove(r.id); }
        return 1;
    }
}
