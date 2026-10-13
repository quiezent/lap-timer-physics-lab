package app.tracklog;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Copies a finished session csv into the public Downloads/TrackLogger folder (no storage permission needed). */
final class Exporter {

    static final String PREFS = "tracklog";
    static final String KEY_URI = "last_export_uri";
    static final String KEY_NAME = "last_export_name";

    private Exporter() { }

    /** Newest session_*.csv in the app's private folder, or null. */
    static File newestSession(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        File best = null;
        File[] lists = new File[]{dir, ctx.getFilesDir()};
        for (File d : lists) {
            if (d == null) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.getName().startsWith("session_") && f.getName().endsWith(".csv")) {
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
            }
        }
        return best;
    }

    /** Returns a one-line human message. Never throws. Also fills Recorder.LIVE.exportText / lastExportUri. */
    static String exportToDownloads(Context ctx, File src) {
        String msg;
        ContentResolver cr = null;
        Uri uri = null;
        try {
            if (src == null || !src.exists() || src.length() == 0) {
                msg = "Nothing to export.";
            } else {
                cr = ctx.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TrackLogger");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("MediaStore refused the file");

                // try-with-resources: an error while closing the output (the final flush to storage) is a failure too
                try (InputStream in = new FileInputStream(src);
                     OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) throw new IOException("cannot open output");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);

                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                sp.edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, src.getName()).apply();
                Recorder.LIVE.lastExportUri = uri.toString();
                msg = String.format(Locale.US, "Saved: Downloads/TrackLogger/%s (%.1f MB)", src.getName(), src.length() / 1048576.0);
            }
        } catch (Exception e) {
            if (cr != null && uri != null) {
                // do not leave an invisible half-written "pending" entry behind
                try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            }
            msg = "EXPORT FAILED (" + e.getMessage() + "). The recording is safe inside the app: "
                    + "free some space and tap Re-export last session.";
        }
        Recorder.LIVE.exportText = msg;
        return msg;
    }
}
