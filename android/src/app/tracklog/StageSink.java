package app.tracklog;

/**
 * What the recorder offers the stage feature. The recorder calls these on its own sensor thread (onStop on whichever thread stops
 * it), and wraps every call in try/catch, so a fault in the stage code can never disturb the recording. Plain Java with no Android
 * classes, so the code behind it is tested on a computer.
 *
 * All times are seconds since the recording's START, on the same clock and with the same digits as the rows written to the file, so
 * a run scored live and the same run scored later from the file give the same numbers.
 */
interface StageSink {

    /** The recorder's side: write an event row, and read the clock. */
    interface Out {
        /** Writes "E,t_us,text" (text is "NAME" or "NAME,values"). Does nothing if the recording has stopped. */
        void event(double tS, String text);

        /** Now, on the recording clock. */
        double now();
    }

    /** Called once, when the sink is handed to the recorder. */
    void attach(Out out);

    /** Every GPS fix, as written to the file. */
    void onFix(Fix f, Out out);

    /** About once a second, whether or not fixes arrive. */
    void onTick(double nowS, Out out);

    /** The recording is being stopped. Anything still open must be closed now: the STOP row follows. */
    void onStop(double nowS, Out out);
}
