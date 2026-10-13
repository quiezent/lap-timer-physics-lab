package app.tracklog;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Backs the stages up as one zip file in the public Downloads/TrackLogger folder (no storage permission needed), and restores them from
 * one the person picks. The file's contents are StageBundle's. The app's own files are deleted when the app is uninstalled (backups are
 * off, see the manifest), so this file is the only copy that survives a reinstall.
 *
 * Both calls block on storage, so the screen runs them on a worker thread. Neither throws.
 */
final class StageExporter {

    static final String MIME = "application/zip";
    static final String KEY_URI = "stage_backup_uri";
    static final String KEY_NAME = "stage_backup_name";

    private StageExporter() { }

    static final class Result {
        final boolean ok;
        final String message;
        final String uri;               // the file written ("" if none)

        Result(boolean ok, String message, String uri) {
            this.ok = ok;
            this.message = message;
            this.uri = uri;
        }
    }

    /** Counts the bytes written, for the message. */
    private static final class Counting extends FilterOutputStream {
        long n;

        Counting(OutputStream out) {
            super(out);
        }

        @Override public void write(int b) throws IOException {
            out.write(b);
            n++;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            n += len;
        }
    }

    static Result backUp(Context ctx, StageStore store, long nowUtcMs) {
        ContentResolver cr = null;
        Uri uri = null;
        try {
            final List<Stage> stages = store.list();
            if (stages.isEmpty()) return new Result(false, "There are no stages to back up yet.", "");
            int runs = 0;
            for (Stage s : stages) runs += store.runs(s.id).size();
            final String name = StageBundle.fileName(nowUtcMs);

            cr = ctx.getContentResolver();
            final ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TrackLogger");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("the phone's file store refused the file");

            long bytes;
            // try-with-resources: an error while closing the output (the final flush to storage) is a failure too
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("cannot open the output file");
                final Counting c = new Counting(os);
                StageBundle.export(store, c);
                bytes = c.n;
            }
            final ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);

            ctx.getSharedPreferences(Exporter.PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, name).apply();
            return new Result(true, String.format(Locale.US,
                    "Saved Downloads/TrackLogger/%s (%d stage%s, %d run%s, %.0f KB). Keep a copy somewhere else too: "
                            + "the app's own files are removed if you uninstall it.",
                    name, stages.size(), stages.size() == 1 ? "" : "s", runs, runs == 1 ? "" : "s", Math.max(1.0, bytes / 1024.0)), uri.toString());
        } catch (Exception e) {
            if (cr != null && uri != null) {
                // do not leave an invisible half-written "pending" entry behind
                try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            }
            return new Result(false, "The backup was not saved (" + e.getMessage() + "). Free some space and try again.", "");
        }
    }

    /** Reads a bundle from a file the person picked and merges it into the store. @return the sentence to show */
    /**
     * The name of a file the person picked, as its provider gives it: for a recording from Downloads that is session_20261013_095638.csv,
     * which is what makes its runs get the same ids however many times it is scored. (Uri.getLastPathSegment is often only a number there.)
     * Blocks on the provider, so not on the screen's thread. Never throws; "picked.csv" if nothing better is known.
     */
    static String displayName(Context ctx, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                final int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) name = c.getString(i);
            }
        } catch (RuntimeException e) {
            name = null;                                    // a provider that refuses the question: fall back below
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (RuntimeException e) {
                    // nothing to do
                }
            }
        }
        if (name == null || name.trim().length() == 0) name = uri.getLastPathSegment();
        if (name == null || name.trim().length() == 0) name = "picked.csv";
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }

    static String restore(Context ctx, Uri uri, StageStore store, long nowUtcMs) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return "That file could not be opened.";
            return StageBundle.importFrom(in, store, nowUtcMs).text();
        } catch (StageBundle.Refused e) {
            return StageText.cap(String.valueOf(e.getMessage())) + ".";
        } catch (IOException e) {
            return "That file could not be read (" + e.getMessage() + ").";
        } catch (RuntimeException e) {                  // e.g. SecurityException when the file provider takes the permission back
            return "That file could not be opened (" + e.getMessage() + ").";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }
}
