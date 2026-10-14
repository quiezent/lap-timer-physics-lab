package app.tracklog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a place (latitude and longitude) from text that the driver typed or pasted, most likely from a map: the two numbers Google Maps
 * shows when you touch and hold a spot, a degrees-minutes-seconds form, a map link, a geo: link. Plain Java with no Android classes.
 * The rules, in order, are section 11.8 of docs/stage-algorithm.md.
 *
 * The aim is that a place is never read as a different place. Anything that could be read two ways is refused with a sentence that
 * says what to write instead; the numbers are never swapped or guessed for the driver.
 */
final class Coords {

    /** The outcome of reading a text: a place, or a sentence saying why not. */
    static final class Result {
        final boolean ok;
        final double lat, lon;
        final String message;           // "" when ok

        private Result(boolean ok, double lat, double lon, String message) {
            this.ok = ok;
            this.lat = lat;
            this.lon = lon;
            this.message = message;
        }

        static Result place(double lat, double lon) {
            return new Result(true, lat, lon, "");
        }

        static Result refused(String why) {
            return new Result(false, Double.NaN, Double.NaN, why);
        }
    }

    static final int MAX_TEXT = 4000;

    private static final String NUM = "(-?\\d+(?:\\.\\d+)?)";
    private static final Pattern LINK_PLACE_DATA = Pattern.compile("!3d" + NUM + "!4d" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_AT = Pattern.compile("@" + NUM + "," + NUM);
    private static final Pattern LINK_QUERY = Pattern.compile("[?&](?:q|ll|query|center)=" + NUM + "(?:,|%2[cC])" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_PLACE = Pattern.compile("/place/" + NUM + "," + NUM, Pattern.CASE_INSENSITIVE);

    private Coords() { }

    /** The place as text for the screen: "3.123457, 101.654321" (six decimals, about 0.1 m). */
    static String format(double lat, double lon) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    /**
     * The white space of the reader, the same on every phone: U+0009 to U+000D, U+001C to U+0020, U+00A0, U+1680, U+2000 to U+200A,
     * U+2028, U+2029, U+202F, U+205F and U+3000 (28 characters). The library's own idea of white space differs between versions of Android.
     */
    static boolean space(char c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** The text with A to Z changed to a to z and nothing else: the links are matched in ASCII only, whatever the phone's language. */
    private static String lowerAscii(String s) {
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            b.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return b.toString();
    }

    private static String trimSpace(String s) {
        int i = 0, j = s.length();
        while (i < j && space(s.charAt(i))) i++;
        while (j > i && space(s.charAt(j - 1))) j--;
        return s.substring(i, j);
    }

    static Result parse(String text) {
        if (text == null) return Result.refused("Nothing to read yet.");
        final String t = trimSpace(text);
        if (t.length() == 0) return Result.refused("Nothing to read yet.");
        if (t.length() > MAX_TEXT) return Result.refused("That is too long to be a place.");
        final String lower = lowerAscii(t);

        // 1. a map link
        final int g = lower.indexOf("google.");
        if (lower.contains("://") || lower.startsWith("www.") || (g >= 0 && lower.indexOf("/maps", g) >= 0)) {
            if (lower.contains("goo.gl/")) {
                return Result.refused("That is a short link, and the phone needs the internet to open it. Open it in Google Maps, touch the "
                        + "numbers under the name of the place to copy them, and paste those here.");
            }
            final Pattern[] order = {LINK_PLACE_DATA, LINK_AT, LINK_QUERY, LINK_PLACE};
            for (Pattern p : order) {
                final Matcher m = p.matcher(t);
                if (m.find()) return check(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), false);
            }
            return Result.refused("I could not find a place in that link. In Google Maps touch and hold the spot, then touch the numbers "
                    + "that appear to copy them.");
        }

        // 2. a geo: link
        if (lower.startsWith("geo:")) {
            String body = t.substring(4);
            final int cut = firstOf(body, '?', ';');
            if (cut >= 0) body = body.substring(0, cut);
            final String[] parts = body.split(",", -1);                  // -1: an empty last part (a trailing comma) is a part, and not a number
            if (parts.length < 2 || parts.length > 3) return Result.refused("I could not read that geo: link.");
            final Double la = plainNumber(parts[0]), lo = plainNumber(parts[1]);
            if (la == null || lo == null || (parts.length == 3 && plainNumber(parts[2]) == null)) return Result.refused("I could not read that geo: link.");
            return check(la, lo, false);
        }

        // 3. plain text
        return parsePlain(t);
    }

    private static int firstOf(String s, char a, char b) {
        final int i = s.indexOf(a), j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    /** A number as a geo: link writes it, `-?\d+(\.\d+)?`, with nothing else in the part, not even a space; null if the part is not one. */
    private static Double plainNumber(String s) {
        if (!s.matches("-?\\d+(\\.\\d+)?")) return null;
        return Double.valueOf(s);
    }

    // ------------------------------------------------------------------ plain text

    private static final class Tok {
        final boolean letter;
        final char c;                   // upper-case hemisphere letter
        final double v;                 // the number
        final boolean signed;           // the number was written with + or -

        Tok(char c) {
            this.letter = true;
            this.c = c;
            this.v = 0.0;
            this.signed = false;
        }

        Tok(double v, boolean signed) {
            this.letter = false;
            this.c = 0;
            this.v = v;
            this.signed = signed;
        }
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isMinus(char c) {
        return c == '-' || c == '−';
    }

    /** Cuts the text into numbers and hemisphere letters; null (and the reason in why[0]) if a character cannot be read. */
    private static List<Tok> tokens(String t, String[] why) {
        final List<Tok> out = new ArrayList<Tok>();
        final int n = t.length();
        int i = 0;
        while (i < n) {
            final char c = t.charAt(i);
            if (space(c) || c == ',' || c == ';') {   // separators (a no-break space too)
                i++;
            } else if ("°º˚'’′\"”″".indexOf(c) >= 0) {   // the marks for degrees, minutes, seconds: ignored
                i++;
            } else if ("NSEWnsew".indexOf(c) >= 0) {
                out.add(new Tok(Character.toUpperCase(c)));
                i++;
            } else {
                final boolean sign = c == '+' || isMinus(c);
                int j = sign ? i + 1 : i;
                final boolean startsNumber = j < n && (digit(t.charAt(j)) || (t.charAt(j) == '.' && j + 1 < n && digit(t.charAt(j + 1))));
                if (!startsNumber) {
                    why[0] = "I can't read that: it has the character \"" + c + "\" in it.";
                    return null;
                }
                final StringBuilder b = new StringBuilder();
                if (sign && isMinus(c)) b.append('-');
                while (j < n && digit(t.charAt(j))) b.append(t.charAt(j++));
                if (j < n && t.charAt(j) == '.') {
                    b.append(t.charAt(j++));
                    while (j < n && digit(t.charAt(j))) b.append(t.charAt(j++));
                }
                String s = b.toString();
                if (s.endsWith(".")) s = s + "0";
                if (s.startsWith("-.")) s = "-0" + s.substring(1);
                else if (s.startsWith(".")) s = "0" + s;
                if (j < n && (t.charAt(j) == '.' || t.charAt(j) == '+' || isMinus(t.charAt(j)))) {
                    // 3.1234.5 or 3.1234-101.5678: two numbers with nothing between them are a typing mistake, not a place
                    why[0] = "I can't read that: put a space or a comma between the numbers.";
                    return null;
                }
                out.add(new Tok(Double.parseDouble(s), sign));
                i = j;
            }
        }
        return out;
    }

    private static Result parsePlain(String t) {
        final String[] why = {""};
        final List<Tok> toks = tokens(t, why);
        if (toks == null) return Result.refused(why[0]);
        int letters = 0, numbers = 0;
        for (Tok k : toks) {
            if (k.letter) letters++; else numbers++;
        }
        if (letters == 0) {
            if (numbers == 0) return Result.refused("I can't find numbers in that. A place looks like 3.1234, 101.5678.");
            if (numbers == 1) return Result.refused("I found only one number. A place is two: latitude and longitude, like 3.1234, 101.5678.");
            if (numbers > 2) {
                return Result.refused("I found " + numbers + " numbers. A place is two: latitude and longitude, like 3.1234, 101.5678 "
                        + "(a dot for the decimals, a comma between the two).");
            }
            final double a = toks.get(0).v, b = toks.get(1).v;
            return check(a, b, true);
        }

        // with hemisphere letters: one of N S and one of E W; a letter before its numbers, or after them
        if (letters != 2) {
            return Result.refused("With letters I need one of N or S and one of E or W, for example 3.1234 N, 101.5678 E.");
        }
        final boolean letterFirst = toks.get(0).letter;
        final List<Tok> g1 = new ArrayList<Tok>(), g2 = new ArrayList<Tok>();
        char l1, l2;
        int i = 0;
        final int n = toks.size();
        if (letterFirst) {
            l1 = toks.get(i++).c;
            while (i < n && !toks.get(i).letter) g1.add(toks.get(i++));
            l2 = toks.get(i++).c;                                 // there is a second letter: two were counted
            while (i < n) g2.add(toks.get(i++));
        } else {
            while (!toks.get(i).letter) g1.add(toks.get(i++));
            l1 = toks.get(i++).c;
            while (!toks.get(i).letter) g2.add(toks.get(i++));
            l2 = toks.get(i++).c;
            if (i != n) return Result.refused("There are numbers after the last letter. Write each part as numbers and then N, S, E or W.");
        }
        final boolean ns1 = l1 == 'N' || l1 == 'S', ns2 = l2 == 'N' || l2 == 'S';
        if (ns1 == ns2) return Result.refused("I need one of N or S and one of E or W, for example 3.1234 N, 101.5678 E.");
        final double[] v1 = new double[1], v2 = new double[1];
        final String r1 = partValue(g1, l1, v1);
        if (r1 != null) return Result.refused(r1);
        final String r2 = partValue(g2, l2, v2);
        if (r2 != null) return Result.refused(r2);
        return check(ns1 ? v1[0] : v2[0], ns1 ? v2[0] : v1[0], false);
    }

    /** The value of one part (latitude or longitude) written with a hemisphere letter, put in out[0]; null if fine, else the reason. */
    private static String partValue(List<Tok> g, char hemi, double[] out) {
        if (g.size() < 1 || g.size() > 3) {
            return "Latitude and longitude each take degrees, or degrees and minutes, or degrees, minutes and seconds.";
        }
        for (Tok k : g) {
            if (k.signed) return "A + or - sign together with N, S, E or W is confusing. Use the letter or the sign, not both.";
        }
        final double d = g.get(0).v;
        double m = 0.0, s = 0.0;
        if (g.size() >= 2) {
            if (d != Math.floor(d)) return "When there are minutes, the degrees must be a whole number.";
            m = g.get(1).v;
            if (m >= 60.0) return "Minutes must be less than 60.";
        }
        if (g.size() == 3) {
            if (m != Math.floor(m)) return "When there are seconds, the minutes must be a whole number.";
            s = g.get(2).v;
            if (s >= 60.0) return "Seconds must be less than 60.";
        }
        final double v = d + m / 60.0 + s / 3600.0;
        out[0] = (hemi == 'S' || hemi == 'W') ? -v : v;
        return null;
    }

    // ------------------------------------------------------------------ range

    private static Result check(double lat, double lon, boolean plainPair) {
        if (Math.abs(lat) > 90.0) {
            final String swap = plainPair && Math.abs(lon) <= 90.0 ? " Did you write longitude first? Latitude comes first." : "";
            return Result.refused("Latitude must be between -90 and 90, and " + trim(lat) + " is not." + swap);
        }
        if (Math.abs(lon) > 180.0) return Result.refused("Longitude must be between -180 and 180, and " + trim(lon) + " is not.");
        if (lat == 0.0 && lon == 0.0) return Result.refused("0, 0 is not a place on a road (it is what a failed look-up gives).");
        return Result.place(lat, lon);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%s", Double.toString(Math.rint(v * 1e6) / 1e6));
    }
}
