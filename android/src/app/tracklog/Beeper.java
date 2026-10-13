package app.tracklog;

import android.media.AudioManager;
import android.media.ToneGenerator;

/**
 * The only thing the phone does while the car is moving: one beep at the start line, two at the finish line, a low tone if the run is
 * given up. No screen, no speech, no numbers. Never throws: if the phone cannot make the sound, nothing happens.
 *
 * The tones are the ones Android's ToneGenerator documents as lasting 0.2 to 0.4 s (PROMPT, ACK, NACK). The shorter ones (BEEP and BEEP2
 * are 35 ms ticks) would be easy to miss over road noise. They play on the media volume, so they follow the car's speakers if the phone
 * is connected to them. I have not heard them on a phone.
 */
final class Beeper {

    private ToneGenerator tg;
    private boolean enabled = true;
    private boolean broken;

    synchronized void setEnabled(boolean on) {
        enabled = on;
    }

    private void tone(int tone, int ms) {
        if (!enabled || broken) return;
        try {
            if (tg == null) tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tg.startTone(tone, ms);
        } catch (RuntimeException e) {
            broken = true;                       // e.g. no audio resources: do not try again on every line
        }
    }

    synchronized void start() {
        tone(ToneGenerator.TONE_PROP_PROMPT, 300);
    }

    synchronized void finish() {
        tone(ToneGenerator.TONE_PROP_ACK, 400);
    }

    synchronized void fail() {
        tone(ToneGenerator.TONE_PROP_NACK, 400);
    }

    synchronized void release() {
        try {
            if (tg != null) tg.release();
        } catch (RuntimeException ignored) {
        }
        tg = null;
    }
}
