package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** FAKE: a dialog is a record of what was asked; a test presses its buttons with click(). */
public class AlertDialog implements DialogInterface {
    public static final List<AlertDialog> SHOWN = new ArrayList<>();

    public String title = "", message = "";
    public View view;
    public String positive, negative;
    public boolean dismissed;
    private DialogInterface.OnClickListener onPositive, onNegative;

    @Override public void dismiss() { dismissed = true; }

    /** Presses BUTTON_POSITIVE or BUTTON_NEGATIVE. */
    public void click(int which) {
        final DialogInterface.OnClickListener l = which == BUTTON_POSITIVE ? onPositive : onNegative;
        dismissed = true;
        if (l != null) l.onClick(this, which);
    }

    public static AlertDialog last() { return SHOWN.isEmpty() ? null : SHOWN.get(SHOWN.size() - 1); }

    public static class Builder {
        private final AlertDialog d = new AlertDialog();
        public Builder(Context c) { }
        public Builder setTitle(CharSequence t) { d.title = String.valueOf(t); return this; }
        public Builder setMessage(CharSequence m) { d.message = String.valueOf(m); return this; }
        public Builder setView(View v) { d.view = v; return this; }
        public Builder setPositiveButton(CharSequence label, DialogInterface.OnClickListener l) { d.positive = String.valueOf(label); d.onPositive = l; return this; }
        public Builder setNegativeButton(CharSequence label, DialogInterface.OnClickListener l) { d.negative = String.valueOf(label); d.onNegative = l; return this; }
        public AlertDialog show() { SHOWN.add(d); return d; }
    }
}
