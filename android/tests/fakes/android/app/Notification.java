package android.app;

import android.content.Context;

/** FAKE */
public class Notification {
    public static class Builder {
        public Builder(Context c, String channelId) { }
        public Builder setContentTitle(CharSequence t) { return this; }
        public Builder setContentText(CharSequence t) { return this; }
        public Builder setSmallIcon(int icon) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Builder setContentIntent(PendingIntent pi) { return this; }
        public Notification build() { return new Notification(); }
    }
}
