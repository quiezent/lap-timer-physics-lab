package android.app;

import android.content.Context;
import android.content.Intent;

/** FAKE */
public class PendingIntent {
    public static final int FLAG_IMMUTABLE = 1 << 26;
    public static final int FLAG_UPDATE_CURRENT = 1 << 27;
    public static PendingIntent getActivity(Context c, int requestCode, Intent i, int flags) { return new PendingIntent(); }
}
