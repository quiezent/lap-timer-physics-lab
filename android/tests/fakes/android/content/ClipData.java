package android.content;

import java.util.ArrayList;
import java.util.List;

/** FAKE: text only. */
public class ClipData {

    public static class Item {
        private final CharSequence text;
        public Item(CharSequence text) { this.text = text; }
        public CharSequence getText() { return text; }
        public CharSequence coerceToText(Context c) { return text == null ? "" : text; }
    }

    private final List<Item> items = new ArrayList<>();

    private ClipData(CharSequence text) { items.add(new Item(text)); }

    public static ClipData newPlainText(CharSequence label, CharSequence text) { return new ClipData(text); }

    public int getItemCount() { return items.size(); }
    public Item getItemAt(int i) { return items.get(i); }
}
