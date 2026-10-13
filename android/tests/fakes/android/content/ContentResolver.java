package android.content;

import android.net.Uri;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

/** FAKE: abstract like the real one; the test implements it (app.tracklog.FakeContentResolver). */
public abstract class ContentResolver {
    public abstract Uri insert(Uri url, ContentValues values);
    public abstract OutputStream openOutputStream(Uri uri) throws FileNotFoundException;
    public abstract int update(Uri uri, ContentValues values, String where, String[] selectionArgs);
    public abstract int delete(Uri url, String where, String[] selectionArgs);
    /** Not abstract: the Exporter tests do not read. The stage restore tests do (FakeContentResolver overrides it). */
    public InputStream openInputStream(Uri uri) throws FileNotFoundException { throw new FileNotFoundException("fake ContentResolver: cannot read " + uri); }
    /** Not abstract either; the real one is final. FakeContentResolver answers for picked files. */
    public android.database.Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
}
