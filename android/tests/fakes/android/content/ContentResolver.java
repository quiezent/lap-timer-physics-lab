package android.content;

import android.net.Uri;
import java.io.FileNotFoundException;
import java.io.OutputStream;

/** FAKE: abstract like the real one; the test implements it (app.tracklog.FakeContentResolver). */
public abstract class ContentResolver {
    public abstract Uri insert(Uri url, ContentValues values);
    public abstract OutputStream openOutputStream(Uri uri) throws FileNotFoundException;
    public abstract int update(Uri uri, ContentValues values, String where, String[] selectionArgs);
    public abstract int delete(Uri url, String where, String[] selectionArgs);
}
