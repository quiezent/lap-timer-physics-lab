package android.app;

import android.content.Context;
import android.content.Intent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** FAKE */
public class PendingIntent {
    public static final int FLAG_IMMUTABLE = 1 << 26;
    public static final int FLAG_UPDATE_CURRENT = 1 << 27;

    /** Test hook, not part of the Android API: the intent of every PendingIntent made, in order (what each notification opens). */
    public static final List<Intent> CREATED = new CopyOnWriteArrayList<>();

    public static PendingIntent getActivity(Context c, int requestCode, Intent i, int flags) {
        CREATED.add(i);
        return new PendingIntent();
    }
}
