package android.media;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** FAKE: records the tones that were asked for (test hook), makes no sound. */
public class ToneGenerator {
    // the real values of the constants
    public static final int TONE_PROP_BEEP = 24;
    public static final int TONE_PROP_ACK = 25;
    public static final int TONE_PROP_NACK = 26;
    public static final int TONE_PROP_PROMPT = 27;
    public static final int TONE_PROP_BEEP2 = 28;

    /** test hooks */
    public static final List<String> TONES = new CopyOnWriteArrayList<>();
    public static volatile boolean FAIL_CREATE;

    public ToneGenerator(int streamType, int volume) {
        if (FAIL_CREATE) throw new RuntimeException("no audio resources");
    }

    public boolean startTone(int tone, int ms) {
        TONES.add(tone == TONE_PROP_PROMPT ? "start" : tone == TONE_PROP_ACK ? "finish" : tone == TONE_PROP_NACK ? "fail" : "other");
        return true;
    }

    public void release() { }
}
