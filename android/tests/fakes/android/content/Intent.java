package android.content;

import android.net.Uri;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FAKE */
public class Intent {
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT";
    public static final String CATEGORY_OPENABLE = "android.intent.category.OPENABLE";
    public static final String EXTRA_STREAM = "android.intent.extra.STREAM";
    public static final String EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES";
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 1;

    private String action;
    private final Class<?> target;
    private Uri data;
    private String type;
    private int flags;
    private final List<String> categories = new ArrayList<>();
    private final Map<String, Object> extras = new HashMap<>();

    public Intent() { this.target = null; }
    public Intent(String action) { this.target = null; this.action = action; }
    public Intent(String action, Uri uri) { this.target = null; this.action = action; this.data = uri; }
    public Intent(Context c, Class<?> cls) { this.target = cls; }

    public Intent setAction(String a) { action = a; return this; }
    public String getAction() { return action; }
    public Class<?> getTarget() { return target; }
    public Intent setData(Uri u) { data = u; return this; }
    public Uri getData() { return data; }
    public Intent setType(String t) { type = t; return this; }
    public String getType() { return type; }
    public Intent addCategory(String c) { categories.add(c); return this; }
    public List<String> getCategories() { return categories; }
    public Intent addFlags(int f) { flags |= f; return this; }
    public int getFlags() { return flags; }
    public Intent putExtra(String name, String value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, String[] value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, Uri value) { extras.put(name, value); return this; }
    public String getStringExtra(String name) { Object o = extras.get(name); return o instanceof String ? (String) o : null; }
    public Object getExtra(String name) { return extras.get(name); }

    public static Intent createChooser(Intent target, CharSequence title) {
        Intent i = new Intent("android.intent.action.CHOOSER");
        i.extras.put("android.intent.extra.INTENT", target);
        return i;
    }
}
