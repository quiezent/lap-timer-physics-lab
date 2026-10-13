package android.app;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** FAKE */
public class NotificationManager {
    public static final int IMPORTANCE_LOW = 2;
    public final List<String> channels = new CopyOnWriteArrayList<>();   // test hook
    public void createNotificationChannel(NotificationChannel c) { channels.add(c.id); }
}
