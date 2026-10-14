package android.widget;

import android.content.Context;
import android.view.View;

/** FAKE: keeps the range and the position; a test moves it the way a finger does with userSeek(). */
public class SeekBar extends View {

    public interface OnSeekBarChangeListener {
        void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser);
        void onStartTrackingTouch(SeekBar seekBar);
        void onStopTrackingTouch(SeekBar seekBar);
    }

    private int max = 100, progress;
    private OnSeekBarChangeListener listener;

    public SeekBar(Context c) { super(c); }

    public void setMax(int m) { max = Math.max(0, m); if (progress > max) progress = max; }
    public int getMax() { return max; }
    public void setProgress(int p) {
        final int q = Math.max(0, Math.min(max, p));
        if (q == progress) return;
        progress = q;
        if (listener != null) listener.onProgressChanged(this, q, false);       // as Android does: not from the user
    }
    public int getProgress() { return progress; }
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener l) { listener = l; }

    // ---- test hook, not part of the Android API ----

    /** A finger drags the thumb to p and lets go. */
    public boolean userSeek(int p) {
        if (!shown() || !isEnabled()) return false;
        final int q = Math.max(0, Math.min(max, p));
        if (listener != null) listener.onStartTrackingTouch(this);
        progress = q;
        if (listener != null) {
            listener.onProgressChanged(this, q, true);
            listener.onStopTrackingTouch(this);
        }
        return true;
    }
}
