package android.content;

/** FAKE */
public class Intent {
    private String action;
    private final Class<?> target;
    public Intent() { this(null, null); }
    public Intent(Context c, Class<?> cls) { this.target = cls; }
    public Intent setAction(String a) { action = a; return this; }
    public String getAction() { return action; }
    public Class<?> getTarget() { return target; }
}
