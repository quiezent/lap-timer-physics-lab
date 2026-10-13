package android.provider;

import android.net.Uri;

/** FAKE: only the constants Exporter uses. */
public final class MediaStore {
    public static class MediaColumns {
        public static final String DISPLAY_NAME = "_display_name";
        public static final String MIME_TYPE = "mime_type";
        public static final String RELATIVE_PATH = "relative_path";
        public static final String IS_PENDING = "is_pending";
    }
    public static final class Downloads extends MediaColumns {
        public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/downloads");
    }
}
