package android.database;

/** FAKE: the few methods the app uses. */
public interface Cursor {
    boolean moveToFirst();
    int getColumnIndex(String name);
    boolean isNull(int columnIndex);
    String getString(int columnIndex);
    void close();
}
