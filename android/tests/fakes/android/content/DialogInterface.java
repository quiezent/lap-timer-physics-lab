package android.content;

/** FAKE */
public interface DialogInterface {
    int BUTTON_POSITIVE = -1, BUTTON_NEGATIVE = -2;

    interface OnClickListener {
        void onClick(DialogInterface dialog, int which);
    }

    void dismiss();
}
