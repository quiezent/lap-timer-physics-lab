package android.content;

/** FAKE: one clip, which a test puts there; it can be made to refuse, as a phone may. */
public class ClipboardManager {
    public ClipData clip;                    // test hook: what is on the clipboard
    public RuntimeException refuse;          // test hook: thrown by getPrimaryClip, like a SecurityException on a phone that says no

    public boolean hasPrimaryClip() { return clip != null; }

    public ClipData getPrimaryClip() {
        if (refuse != null) throw refuse;
        return clip;
    }

    public void setPrimaryClip(ClipData c) { clip = c; }
}
