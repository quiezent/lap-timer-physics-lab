package app.tracklog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Release 1.5, the pure part: reading a place from text (Coords), the cleaned path of a recording (Track), and a stage from an
 * ordinary recording and two pins (PinBuilder). Plain Java, no Android classes; the roads are synthetic with exact geometry, so the true
 * time between two points of the road is known, and one check uses the real Sepang recording.
 *
 *   PN.C   Coords: forms that must be read, texts that must be refused, a fuzz, and a round trip through eight ways of writing a place
 *   PN.T   Track: parked and poor fixes left out, spacing, length, passes of a place, refusal of a recording with no drive
 *   PN.B   PinBuilder: lines exactly at the pins, timing of later drives against the exact time, places off the road, every refusal
 *   PN.H   holes in the GPS: a pin where the phone could not time a line is refused, and a stage cannot join two drives
 *   PN.G   the guard against a road that crosses a line again (hairpins stacked 12, 16, 20, 30 m apart)
 *   PN.R   the real Sepang recording
 */
final class PinTests {

    private PinTests() { }

    static void run(File root) {
        System.out.println("\n===== PN: places, pins and planned stages (release 1.5) =====");
        group("PN.C", new Runnable() { public void run() { coordsTests(); } });
        group("PN.T", new Runnable() { public void run() { trackTests(); } });
        group("PN.B (builder)", new Runnable() { public void run() { builderTests(); } });
        group("PN.B (timing)", new Runnable() { public void run() { timingTests(); } });
        group("PN.B (refusals)", new Runnable() { public void run() { refusalTests(); } });
        group("PN.H", new Runnable() { public void run() { holeTests(); breakTests(); } });
        group("PN.G", new Runnable() { public void run() { guardTests(); } });
        group("PN.R", new Runnable() { public void run() {
            try {
                realTests();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        } });
        group("PN.FX", new Runnable() { public void run() {
            final String fx = System.getProperty("fixtures.dir");
            if (fx == null) {
                System.out.println("(PN.FX skipped: pass -Dfixtures.dir=<analysis/stage_fixtures>; tests/run.sh does when the folder exists)");
                return;
            }
            final String real = System.getProperty("real.log");
            try {
                PinFixtures.check(new File(fx), real != null && new File(real).isFile() ? new File(real) : null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        } });
    }

    private static void group(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            Check.ok(name + " tests ran without an exception", false, t.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    static String tx(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    /** Fixes of a drive over [sFrom, sTo] at 1 Hz: parked for leadS (reported speed about 0), the drive, then parked for tailS. */
    static List<Fix> drive(SynthRoad rd, double sFrom, double sTo, double v0, double scale, double leadS, double tailS, double sigma, long seed) {
        final SynthRoad.Drive d = rd.drive(sFrom, sTo, v0, 15.0, 30.0, 5.5, 3.0, 5.0, scale, null);
        return rd.fixes(d, leadS, 0.0, leadS + d.duration() + tailS, sigma, 0.7, 0.0, new Random(seed));
    }

    static SynthRoad.Drive driveOf(SynthRoad rd, double sFrom, double sTo, double v0, double scale) {
        return rd.drive(sFrom, sTo, v0, 15.0, 30.0, 5.5, 3.0, 5.0, scale, null);
    }

    /** The place (lat, lon) of road position s, moved `left` metres to the left of the road (negative: right). */
    static double[] place(SynthRoad rd, double s, double left) {
        final double[] p = rd.pos(s);
        final double h = Math.toRadians(rd.heading(s));
        return new double[]{rd.lat(p[1] + Math.sin(h) * left), rd.lon(p[0] - Math.cos(h) * left)};
    }

    /** Distance along the track of the first pass of the road point s. */
    static double trackS(Track tr, SynthRoad rd, double s) {
        final double[] pl = place(rd, s, 0.0);
        final List<Track.Pass> ps = tr.passes(pl[0], pl[1]);
        return ps.isEmpty() ? Double.NaN : ps.get(0).s;
    }

    static String category(Runnable r) {
        try {
            r.run();
            return "";
        } catch (PinBuilder.Refused e) {
            return e.category;
        } catch (RuntimeException e) {
            return "EXCEPTION " + e;
        }
    }

    /** Road position of a gate's centre. */
    static double gateRoadS(SynthRoad rd, Stage st, Gate g) {
        final double[] xy = StageTests.gateXY(rd, st, g);
        return StageTests.roadS(rd, xy[0], xy[1]);
    }

    // ------------------------------------------------------------------ PN.C

    static final double[][] READ_OK = {
            // text is in READ_TEXT, expected lat, lon here
            {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {-33.8688, 151.2093}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {-33.8688, 151.2093}, {-33.8688, 151.2093}, {3.1234, -101.5678},
            {3.1390, 101.686861111}, {3.1390, 101.686861111}, {3.1390, 101.686861111}, {3.1390, 101.686861111},
            {-33.8688, 151.2093}, {3.1234, 101.5678}, {0.5, 0.25}, {3.0, 101.0},
            {3.1390, 101.6869}, {3.1569, 101.7123}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1, 101.5},
            {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {90.0, 180.0}, {-90.0, -180.0}, {3.1234, 101.5678}, {3.139, 101.68666666666667}, {3.139, 101.68666666666667}, {-3.1234, -101.5678},
            {3.1333333333333333, 101.68333333333334}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {3.2, 101.6}, {3.1234, 101.5678}, {3.1, 101.5}, {3.1234, 101.5678},
            // the edges of the reader (section 11.8): which link part wins, the case of the letters, the white space, the length
            {3.2, 101.6}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1390, 101.6869}, {1.0, 2.0}, {3.1234, 101.5678},
            {3.1234, 101.5678}, {-3.1234, -101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678}, {3.1234, 101.5678},
            {3.1234, 101.5678}, {-3.1234, 101.5678}, {3.1234, 101.5678}
    };

    /** n spaces (the filler of the texts at the length limit). */
    static String pad(int n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(' ');
        return b.toString();
    }

    static final String[] READ_TEXT = {
            "3.1234, 101.5678", "3.1234 101.5678", "3.1234,101.5678", "  3.1234 ,  101.5678  ", "3.1234;101.5678",
            "-33.8688, 151.2093", "+3.1234, +101.5678", "3.1234° 101.5678°", "3.1234°, 101.5678°",
            "3.1234 N, 101.5678 E", "3.1234N 101.5678E", "N 3.1234 E 101.5678", "N3.1234, E101.5678", "101.5678 E, 3.1234 N",
            "S 33.8688 E 151.2093", "33.8688 S, 151.2093 E", "3.1234 N, 101.5678 W",
            "3°08'20.4\"N 101°41'12.7\"E", "3° 8' 20.4\" N, 101° 41' 12.7\" E", "3 8 20.4 N 101 41 12.7 E",
            "3°08′20.4″N 101°41′12.7″E",
            "−33.8688, 151.2093", "3.1234\n101.5678", ".5, .25", "3., 101.",
            "https://www.google.com/maps/place/Kuala+Lumpur/@3.1569,101.7123,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!7e2!8m2!3d3.1390!4d101.6869",
            "https://www.google.com/maps/@3.1569,101.7123,17z", "https://www.google.com/maps?q=3.1234,101.5678",
            "https://maps.google.com/?q=3.1234%2C101.5678", "https://www.google.com/maps?ll=3.1234,101.5678&z=15",
            "https://www.google.com/maps/place/3.1234,101.5678", "https://www.google.com/maps/search/?api=1&query=3.1234,101.5678",
            "www.google.com/maps/@3.1,101.5,15z",
            "geo:3.1234,101.5678", "geo:3.1234,101.5678?q=3.1234,101.5678(Start)", "geo:3.1234,101.5678,25", "GEO:3.1234,101.5678;u=35",
            "3.1234,\u00A0101.5678", "3.1234\u00A0101.5678", "3.1234\u202F101.5678",
            "90, 180", "-90, -180", "3.1234 n, 101.5678 e", "3 8.34 N 101 41.2 E", "N 3 8.34 E 101 41.2", "-3.1234, -101.5678",
            "3°8'N, 101°41'E", "3.1234\u2003101.5678", "3.1234\u2028101.5678", "3.1234\u001F101.5678",
            "https://www.google.com/maps/place/3.1234,101.5678/@3.2,101.6,17z", "https://www.google.com/maps?q=3.1234%2c101.5678",
            "google.com/maps/@3.1,101.5,15z", "HTTPS://WWW.GOOGLE.COM/MAPS/@3.1234,101.5678,15Z",
            // which part of a link wins: the place itself (!3d!4d), then @, then q= ll= query= center= (the leftmost), then /place/
            "https://www.google.com/maps/@3.2,101.6,17z?q=3.1234,101.5678",
            "https://www.google.com/maps?center=3.1234,101.5678&zoom=15",
            "https://www.google.com/maps?Q=3.1234%2c101.5678",
            "HTTPS://MAPS.GOOGLE.COM/?LL=3.1234,101.5678",
            "https://www.google.com/maps/@3.1569,101.7123,17z/data=!3d3.1390!4d101.6869",
            "https://www.google.com/maps?q=1.0,2.0&ll=3.1234,101.5678",
            "WWW.GOOGLE.COM/MAPS/PLACE/3.1234,101.5678",
            // geo: links: the altitude, what is cut off, the case, no white space inside
            "geo:3.1234,101.5678,25?z=3", "  GeO:-3.1234,-101.5678  ", "geo:3.1234,101.5678?", "geo:3.1234,101.5678;crs=wgs84", "geo:3.1234,101.5678,-12.5",
            // white space at the ends, of the 28 characters of the reader
            "\u00A03.1234, 101.5678\u00A0", "\u30003.1234 101.5678\u3000", "\u2009\u200A3.1234 101.5678\u2028\u2029", "\u001C\u001D3.1234 101.5678\u001E\u001F", "\u1680\u202F\u205F3.1234 101.5678",
            "\t\r\n3.1234\u000B101.5678\u000C",
            // the length limit: 4000 characters are read, after the white space at the ends is cut off
            "3.1234" + pad(3986) + "101.5678", pad(100) + "S 3.1234" + pad(3000) + "E 101.5678" + pad(500),
            "3.1234, 101.5678" + pad(4000)
    };

    static final String[] READ_REFUSED = {
            "", "   ", "hello", "3.1234", "3.1234, 101.5678, 12", "3,1234 101,5678", "101.5678, 3.1234", "91, 10", "3.1234, 181",
            "0, 0", "0.0 N 0.0 E", "3.1234 N 101.5678", "3.1234 N 101.5678 S", "N N 3 5", "-3.1234 N, 101.5678 E",
            "3°65'00\"N 101°41'12.7\"E", "3.5°08'20.4\"N 101°41'12.7\"E", "3 N 101 E 7",
            "https://goo.gl/maps/abc123", "https://maps.app.goo.gl/xyz", "https://www.google.com/maps/dir/", "geo:abc",
            "3.1234 abc 101.5678", "3.1234, 101.5678 !", "E 101.5678 N", "N 3.1234", "3 8 60 N 101 41 12 E", "3 8.5 20 N 101 41 12 E",
            "1e3, 4", "3.1234.5, 101.5", "--3, 101", "3 N N 101 E", "N E 3 101", "95 N 101 E", "3 N 185 E",
            "3.1234.5", "3.1234-101.5678", "3.1234+101.5678", "\u0663.\u0661\u0662\u0663\u0664, \u0661\u0660\u0661.\u0665\u0666\u0667\u0668",
            "\uFF13.\uFF11\uFF12\uFF13\uFF14, \uFF11\uFF10\uFF11.\uFF15\uFF16\uFF17\uFF18", "3.1234,\u0085101.5678", "3.1234\u200B101.5678",
            "maps?q=3.1234,101.5678", "https://www.google.com/maps/dir/3.1,101.5/3.2,101.6", "3.1234 101.5678 N E", "90.0000001, 10", "10, 180.0000001",
            // the edges of the reader: characters that are not white space, geo: links with anything extra, a link with no match
            "3.1234" + pad(3987) + "101.5678", "\u00853.1234, 101.5678", "3.1234, 101.5678\u180E", "\uFEFF3.1234, 101.5678", "3.1234,\u200B101.5678", "3.1234\u2060101.5678",
            "geo:3.1234, 101.5678", "geo: 3.1234,101.5678", "geo:3.1234,101.5678,", "geo:3.1234,101.5678,abc", "geo:3.1234", "geo:3.1234,101.5678,1,2",
            "geo:3.1234;101.5678", "geo:3.1234,\u00A0101.5678", "geo:,101.5678", "geo:3.1234,,101.5678", "geo:+3.1234,101.5678", "geo:3.,101.5678", "geo:.5,101.5678",
            "3.1234 + 101.5678", "N +3.1234 E 101.5678", "3.1234 +N 101.5678 E", "3.1234 Z 101.5678", "3.1234 \u00D7 101.5678",
            "https://www.google.com/maps/@3.1569, 101.7123,17z", "https://www.google.com/maps?q=3.1234%2C%20101.5678",
            "https://maps.google.com/?q=3.1234,101.5678&x=goo.gl/abc", "https://www.google.com/maps?z=15&q=a,b", "https://www.google.com/maps/@\uFF13.1,101.5,15z"
    };

    static void coordsTests() {
        // PN.C1 forms that must be read
        int bad = 0;
        String first = "";
        for (int i = 0; i < READ_TEXT.length; i++) {
            final Coords.Result r = Coords.parse(READ_TEXT[i]);
            final boolean good = r.ok && r.message.length() == 0 && Math.abs(r.lat - READ_OK[i][0]) < 1e-6 && Math.abs(r.lon - READ_OK[i][1]) < 1e-6;
            if (!good) {
                bad++;
                if (first.length() == 0) first = READ_TEXT[i] + " -> " + (r.ok ? Coords.format(r.lat, r.lon) : r.message);
            }
        }
        Check.ok("PN.C1 " + READ_TEXT.length + " ways of writing a place (decimal pairs, N/S/E/W before or after, degrees-minutes-seconds with real and look-alike marks, minus sign, Google Maps links, geo: links) are read correctly",
                bad == 0 && READ_TEXT.length == READ_OK.length, bad == 0 ? "" : bad + " wrong, first: " + first);

        // PN.C2 texts that must be refused
        bad = 0;
        first = "";
        for (String s : READ_REFUSED) {
            final Coords.Result r = Coords.parse(s);
            if (r.ok || r.message.length() == 0) {
                bad++;
                if (first.length() == 0) first = s + " -> " + Coords.format(r.lat, r.lon);
            }
        }
        final Coords.Result nul = Coords.parse(null);
        final StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 900; i++) longText.append("3.1234 ");
        final Coords.Result lng = Coords.parse(longText.toString());
        Check.ok("PN.C2 " + READ_REFUSED.length + " texts that are not one place (one number, three numbers, comma decimals, swapped, out of range, 0 0, a single letter, two latitudes, a sign with a letter, minutes of 60, short links, junk) are refused with a sentence; so are null and a 6000-character text",
                bad == 0 && !nul.ok && nul.message.length() > 0 && !lng.ok && lng.message.length() > 0, bad == 0 ? "" : bad + " accepted, first: " + first);

        // PN.C3 the sentences say what to do
        final String swap = Coords.parse("101.5678, 3.1234").message, four = Coords.parse("3,1234 101,5678").message;
        final String shortLink = Coords.parse("https://maps.app.goo.gl/xyz").message, sign = Coords.parse("-3.1234 N, 101.5678 E").message;
        Check.ok("PN.C3 the refusals help: a swapped pair says latitude comes first, comma decimals say how many numbers were found and to use a dot, a short link says to copy the numbers instead, a sign next to a letter says so",
                swap.toLowerCase(Locale.US).contains("latitude comes first") && four.contains("4 numbers") && four.contains("dot")
                        && shortLink.contains("short link") && shortLink.contains("copy") && sign.contains("sign"),
                swap + " | " + four + " | " + shortLink + " | " + sign);

        // PN.C4 a swapped pair is never swapped for the driver, a valid pair is never changed
        final Coords.Result a = Coords.parse("3.1234, 101.5678"), b = Coords.parse("101.5678, 3.1234");
        Check.ok("PN.C4 3.1234, 101.5678 is latitude then longitude; the same numbers the other way round are refused, not swapped",
                a.ok && a.lat == 3.1234 && a.lon == 101.5678 && !b.ok);

        // PN.C5 fuzz: random text never throws, and whatever is accepted is a place that exists
        final Random rnd = new Random(77);
        final String alphabet = "0123456789.,;- +NSEWnsew°'\"@:/=!?&%dqlcgeopahtw−′″\n";
        int accepted = 0, thrown = 0, wrong = 0;
        for (int i = 0; i < 20000; i++) {
            final int len = rnd.nextInt(41);
            final StringBuilder sb = new StringBuilder();
            for (int k = 0; k < len; k++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            try {
                final Coords.Result r = Coords.parse(sb.toString());
                if (r.ok) {
                    accepted++;
                    if (!(Math.abs(r.lat) <= 90.0 && Math.abs(r.lon) <= 180.0) || (r.lat == 0.0 && r.lon == 0.0) || r.message.length() != 0
                            || Double.isNaN(r.lat) || Double.isNaN(r.lon)) wrong++;
                } else if (r.message.length() == 0) {
                    wrong++;
                }
            } catch (RuntimeException e) {
                thrown++;
            }
        }
        Check.ok("PN.C5 20000 random texts: none throws, " + accepted + " were read as a place and every one of those is inside the range and not 0, 0; every refusal has a sentence",
                thrown == 0 && wrong == 0, "thrown=" + thrown + " wrong=" + wrong);

        // PN.C6 round trip: 3000 random places written eight ways and read back
        final int nForms = 8;
        final int[] failed = new int[nForms];
        final double[] worst = new double[nForms];
        for (int i = 0; i < 3000; i++) {
            final double lat = (rnd.nextDouble() * 2 - 1) * 89.99, lon = (rnd.nextDouble() * 2 - 1) * 179.99;
            if (Math.abs(lat) < 1e-3 && Math.abs(lon) < 1e-3) continue;
            final String[] forms = {
                    String.format(Locale.US, "%.7f, %.7f", lat, lon),
                    String.format(Locale.US, "%.7f %.7f", lat, lon),
                    String.format(Locale.US, "%.6f° %s, %.6f° %s", Math.abs(lat), lat < 0 ? "S" : "N", Math.abs(lon), lon < 0 ? "W" : "E"),
                    String.format(Locale.US, "%s %.6f %s %.6f", lat < 0 ? "S" : "N", Math.abs(lat), lon < 0 ? "W" : "E", Math.abs(lon)),
                    dms(lat, "N", "S") + " " + dms(lon, "E", "W"),
                    String.format(Locale.US, "https://www.google.com/maps/@%.7f,%.7f,15z", lat, lon),
                    String.format(Locale.US, "https://www.google.com/maps?q=%.7f%%2C%.7f", lat, lon),
                    String.format(Locale.US, "geo:%.7f,%.7f?q=%.7f,%.7f(Start)", lat, lon, lat, lon)
            };
            for (int f = 0; f < nForms; f++) {
                final Coords.Result r = Coords.parse(forms[f]);
                final double err = r.ok ? Math.max(Math.abs(r.lat - lat), Math.abs(r.lon - lon)) : 1e9;
                worst[f] = Math.max(worst[f], err);
                if (!(err < 2e-6)) failed[f]++;
            }
        }
        int totalFailed = 0;
        double worstAll = 0.0;
        for (int f = 0; f < nForms; f++) {
            totalFailed += failed[f];
            worstAll = Math.max(worstAll, worst[f]);
        }
        Check.ok("PN.C6 3000 random places written eight ways (decimal pair, spaces, degree marks with letters after, letters before, degrees-minutes-seconds, a map link with @, a map link with q=...%2C..., a geo: link) all read back to within 2e-6 degrees (0.2 m): "
                        + totalFailed + " failed, worst " + String.format(Locale.US, "%.1e", worstAll) + " degrees",
                totalFailed == 0);

        // PN.C7 how a place is shown
        Check.ok("PN.C7 a place is shown as latitude, longitude to six decimals", Coords.format(3.1234567, 101.5678912).equals("3.123457, 101.567891"),
                Coords.format(3.1234567, 101.5678912));
    }

    static String dms(double v, String pos, String neg) {
        final double a = Math.abs(v);
        int d = (int) Math.floor(a);
        int m = (int) Math.floor((a - d) * 60.0);
        double s = ((a - d) * 60.0 - m) * 60.0;
        if (s >= 59.99995) {
            s = 0.0;
            m++;
            if (m >= 60) {
                m = 0;
                d++;
            }
        }
        return String.format(Locale.US, "%d°%02d'%07.4f\"%s", d, m, s, v < 0 ? neg : pos);
    }

    // ------------------------------------------------------------------ PN.T

    static void trackTests() {
        final SynthRoad rd = SynthRoad.mountain();

        // PN.T1 parked lead-in and tail are left out; vertices are at least 3 m apart; the length is the road's
        final List<Fix> f1 = drive(rd, -400.0, rd.total + 300.0, 18.0, 1.0, 40.0, 30.0, 0.0, 1);
        final Track t1 = Track.of(f1);
        double minGap = Double.MAX_VALUE;
        for (int i = 1; i < t1.n; i++) minGap = Math.min(minGap, t1.route.s[i] - t1.route.s[i - 1]);
        double minChord = Double.MAX_VALUE;
        for (int i = 1; i < t1.n; i++) minChord = Math.min(minChord, Math.hypot(t1.route.x[i] - t1.route.x[i - 1], t1.route.y[i] - t1.route.y[i - 1]));
        final double want = rd.total + 700.0;
        Check.ok(String.format(Locale.US, "PN.T1 a drive of %.0f m with 40 s parked before and 30 s after: the track is %.0f m (within 1.5 %%), has %d vertices, none closer than 3 m (smallest step %.2f m)", want, t1.length, t1.n, minChord),
                Math.abs(t1.length / want - 1.0) < 0.015 && minChord >= 3.0 - 1e-9 && minGap >= 3.0 - 1e-9 && t1.n > 100);

        // the track starts where the car set off, not where it was parked: the first vertex is the first moving fix
        final double[] p0 = rd.pos(-400.0);
        final double d0 = Math.hypot(rd.geo.x(t1.lon[0]) - p0[0], rd.geo.y(t1.lat[0]) - p0[1]);
        Check.ok(String.format(Locale.US, "PN.T2 the first vertex is within 60 m of where the car set off (it was parked there 40 s): %.1f m", d0), d0 < 60.0);

        // PN.T3 noisy GPS: length within 3 %
        final Track t3 = Track.of(drive(rd, -400.0, rd.total + 300.0, 18.0, 1.0, 40.0, 30.0, 3.0, 2));
        Check.ok(String.format(Locale.US, "PN.T3 the same drive with 3 m of GPS noise: %.0f m (noise zigzags the path and makes it longer; limit 3 %% over %.0f m)", t3.length, want),
                t3.length > want * 0.99 && t3.length < want * 1.03);

        // PN.T4 poor fixes (accuracy 50 m, displaced 200 m) are left out
        final List<Fix> f4 = new ArrayList<Fix>(f1);
        for (int i = 10; i < f4.size(); i += 10) {
            final Fix f = f4.get(i);
            f4.set(i, new Fix(f.t, f.lat + 0.0018, f.lon, f.speed, f.bearing, 50.0));
        }
        final Track t4 = Track.of(f4);
        double dev = 0.0;
        for (int i = 0; i < t4.n; i++) {
            double best = Double.MAX_VALUE;
            for (double s = -450.0; s <= rd.total + 350.0; s += 2.0) {
                final double[] p = rd.pos(s);
                best = Math.min(best, Math.hypot(rd.geo.x(t4.lon[i]) - p[0], rd.geo.y(t4.lat[i]) - p[1]));
            }
            dev = Math.max(dev, best);
        }
        Check.ok(String.format(Locale.US, "PN.T4 one fix in ten displaced 200 m with a reported accuracy of 50 m: none of them is a vertex (the track stays within %.1f m of the road)", dev), dev < 2.0);

        // PN.T5 order and duplicates do not matter
        final List<Fix> f5 = new ArrayList<Fix>(f1);
        f5.addAll(f1.subList(100, 200));
        Collections.shuffle(f5, new Random(5));
        final Track t5 = Track.of(f5);
        boolean same = t5.n == t1.n && t5.length == t1.length;
        for (int i = 0; same && i < t1.n; i++) same = t5.lat[i] == t1.lat[i] && t5.lon[i] == t1.lon[i] && t5.t[i] == t1.t[i];
        Check.ok("PN.T5 the fixes shuffled, with 100 of them twice, give exactly the same track", same);

        // PN.T6 recordings with no drive are refused with the category no_drive
        final List<Fix> parked = new ArrayList<Fix>();
        for (int i = 0; i < 120; i++) parked.add(new Fix(i, 3.5, 101.6, 0.0, -1.0, 4.0));
        final List<Fix> two = new ArrayList<Fix>(f1.subList(60, 62));
        final List<Fix> slow = new ArrayList<Fix>();
        for (int i = 0; i < 100; i++) slow.add(new Fix(i, 3.5 + i * 1e-6, 101.6, 5.0, 0.0, 4.0));         // moving, but 11 cm apart in all
        final List<Fix> poor = new ArrayList<Fix>();
        for (int i = 0; i < 100; i++) poor.add(new Fix(i, 3.5 + i * 1e-4, 101.6, 10.0, 0.0, 80.0));        // moving, but never an accurate fix
        final String c1 = category(new Runnable() { public void run() { Track.of(new ArrayList<Fix>()); } });
        final String c2 = category(new Runnable() { public void run() { Track.of(parked); } });
        final String c3 = category(new Runnable() { public void run() { Track.of(two); } });
        final String c4 = category(new Runnable() { public void run() { Track.of(poor); } });
        Check.ok("PN.T6 no fixes, a parked phone, two fixes, and a drive with no accurate fix are each refused as no_drive",
                c1.equals("no_drive") && c2.equals("no_drive") && c3.equals("no_drive") && c4.equals("no_drive"),
                c1 + " " + c2 + " " + c3 + " " + c4);
        final Track tiny = Track.of(slow);
        final String c5 = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tiny, PinBuilder.Pin.atPosition(3.0), PinBuilder.Pin.atPosition(6.0)); } });
        Check.ok(String.format(Locale.US, "PN.T6b a drive of only %.0f m is a track (three vertices are enough), but no pins on it can make a stage: refused as near_end", tiny.length),
                tiny.length > 5.0 && tiny.length < 15.0 && c5.equals("near_end"), c5);

        // PN.T7 passes of a place: one pass, with the offset
        final double sA = trackS(t1, rd, 1500.0);
        final double[] near = place(rd, 1500.0, 8.0);
        final List<Track.Pass> ps = t1.passes(near[0], near[1]);
        final double[] ll = t1.latLonAt(sA);
        final double[] want1 = place(rd, 1500.0, 0.0);
        Check.ok(String.format(Locale.US, "PN.T7 a place 8 m to the left of the road at 1500 m: one pass, offset %.1f m (8 +- 0.5), at the right place on the road (%.2f m)", ps.isEmpty() ? -1 : ps.get(0).offsetM,
                Math.hypot(rd.geo.x(ll[1]) - rd.geo.x(want1[1]), rd.geo.y(ll[0]) - rd.geo.y(want1[0]))),
                ps.size() == 1 && Math.abs(ps.get(0).offsetM - 8.0) < 0.5 && Math.abs(ps.get(0).s - sA) < 3.0
                        && Math.hypot(rd.geo.x(ll[1]) - rd.geo.x(want1[1]), rd.geo.y(ll[0]) - rd.geo.y(want1[0])) < 3.0);

        // PN.T8 a place 100 m from the road is not on it; the nearest approach is reported
        final double[] far = place(rd, 1500.0, 100.0);
        Check.ok(String.format(Locale.US, "PN.T8 a place 100 m from the road has no pass (the limit is 60 m); the track comes no closer than %.1f m", t1.closestM(far[0], far[1])),
                t1.passes(far[0], far[1]).isEmpty() && Math.abs(t1.closestM(far[0], far[1]) - 100.0) < 1.5);

        // PN.T9 out and back: two passes in order; the turning point is one pass
        final SynthRoad flat = SynthRoad.mountain();
        final double turn = 3000.0;
        final List<Fix> out = flat.fixesFn(new java.util.function.DoubleUnaryOperator() {
            public double applyAsDouble(double t) {
                final double s = -200.0 + 15.0 * t;
                return s <= turn ? s : Math.max(-200.0, turn - (s - turn));
            }
        }, 0.0, 2.0 * (turn + 200.0) / 15.0, 0.0, 0.7, new Random(9));
        final Track tb = Track.of(out);
        final double[] pm = place(flat, 1000.0, 3.0), pt = place(flat, turn, 0.0);
        final List<Track.Pass> two1 = tb.passes(pm[0], pm[1]), turnP = tb.passes(pt[0], pt[1]);
        Check.ok("PN.T9 a road driven out and back: a place on it has two passes, in order along the track, the second about 4000 m after the first; the turning point has one",
                two1.size() == 2 && two1.get(0).s < two1.get(1).s && Math.abs((two1.get(1).s - two1.get(0).s) - 2.0 * (turn - 1000.0)) < 40.0 && turnP.size() == 1,
                two1.size() + " passes; turn " + turnP.size());
    }

    // ------------------------------------------------------------------ PN.B

    /** A stage from positions, on a track made from a noise-free drive. */
    static final class Rig {
        final SynthRoad road = SynthRoad.mountain();
        final List<Fix> fixes;
        final Track track;

        Rig(double sigma, long seed) {
            fixes = drive(road, -400.0, road.total + 300.0, 18.0, 1.0, 40.0, 30.0, sigma, seed);
            track = Track.of(fixes);
        }
    }

    static void builderTests() {
        final Rig rig = new Rig(0.0, 3);
        final SynthRoad rd = rig.road;
        final double sa = trackS(rig.track, rd, 600.0), sb = trackS(rig.track, rd, 3200.0);
        final PinBuilder.Result r = PinBuilder.build("st_pin", "Pinned", 1700000000000L, rig.track, PinBuilder.Pin.atPosition(sa), PinBuilder.Pin.atPosition(sb));
        final Stage st = r.stage;

        // PN.B1 the lines are exactly at the pins, perpendicular to the road
        final double gs = gateRoadS(rd, st, st.start), gf = gateRoadS(rd, st, st.finish);
        final double hs = rd.heading(600.0), hf = rd.heading(3200.0);
        Check.ok(String.format(Locale.US, "PN.B1 pins at 600 m and 3200 m of a straight-road drive: the start line is at %.2f m and the finish line at %.2f m of the road (within 1 m of the pins, no 20 m shift as in a marked stage); bearings %.1f and %.1f against the road's %.1f and %.1f",
                gs, gf, st.start.bearingDeg, st.finish.bearingDeg, hs, hf),
                Math.abs(gs - 600.0) < 1.0 && Math.abs(gf - 3200.0) < 1.0 && Geo.angleDiff(st.start.bearingDeg, hs) < 1.0 && Geo.angleDiff(st.finish.bearingDeg, hf) < 1.0
                        && st.start.half == 25.0 && st.finish.half == 25.0);

        // PN.B2 the route runs from the middle of one line to the middle of the other, along the drive, and is as long as the drive between the pins
        final double len = sb - sa;
        Check.ok(String.format(Locale.US, "PN.B2 the route starts in the middle of the start line and ends in the middle of the finish line, and is %.1f m long (the track between the pins is %.1f m)", st.lengthM(), len),
                Math.hypot(st.route.x[0] - st.start.cx, st.route.y[0] - st.start.cy) < 1e-9
                        && Math.hypot(st.route.x[st.route.n - 1] - st.finish.cx, st.route.y[st.route.n - 1] - st.finish.cy) < 1e-9
                        && Math.abs(st.lengthM() - len) < 0.05);

        // PN.B3 the marks and the frame
        final double[] llA = rig.track.latLonAt(sa), llB = rig.track.latLonAt(sb);
        Check.ok("PN.B3 for pins given by position, the marks are the pins' latitudes and longitudes and the frame's origin is the start mark",
                Math.abs(st.startMarkLat - llA[0]) < 1e-12 && Math.abs(st.startMarkLon - llA[1]) < 1e-12
                        && Math.abs(st.finishMarkLat - llB[0]) < 1e-12 && Math.abs(st.finishMarkLon - llB[1]) < 1e-12
                        && st.geo.lat0 == st.startMarkLat && st.geo.lon0 == st.startMarkLon
                        && r.startOffsetM == 0.0 && r.finishOffsetM == 0.0 && r.startPasses == 0 && r.finishPasses == 0);

        // PN.B4 deterministic, and the file reads back to the same stage
        final PinBuilder.Result r2 = PinBuilder.build("st_pin", "Pinned", 1700000000000L, rig.track, PinBuilder.Pin.atPosition(sa), PinBuilder.Pin.atPosition(sb));
        final String text = StageFiles.formatStage(st);
        final Stage back = StageFiles.parseStage(text);
        Check.ok("PN.B4 building twice gives the same file, and the file read back and written again is the same text",
                text.equals(StageFiles.formatStage(r2.stage)) && text.equals(StageFiles.formatStage(back)) && back.kind.equals(Stage.KIND_ROAD));

        // PN.B5 the drive the stage came from is a run of it
        final List<RunResult> runs = Scorer.scoreAll(back, rig.fixes, "session_build.csv", 0.0);
        final SynthRoad.Drive dr = driveOf(rd, -400.0, rd.total + 300.0, 18.0, 1.0);
        final double truth = dr.timeAt(gateRoadS(rd, back, back.finish)) - dr.timeAt(gateRoadS(rd, back, back.start));
        Check.ok(String.format(Locale.US, "PN.B5 the recording the stage was made from scores as one finished run of it, %.3f s against the exact %.3f s (within 30 ms, noise-free)", runs.isEmpty() ? Double.NaN : runs.get(0).totalS, truth),
                runs.size() == 1 && runs.get(0).finished && Math.abs(runs.get(0).totalS - truth) < 0.03);

        // PN.B6 pins given as places 8 m off the road give the same lines, the places become the marks, and the offset is said
        final double[] pa = place(rd, 600.0, 8.0), pb = place(rd, 3200.0, -8.0);
        final PinBuilder.Result rp = PinBuilder.build("st_place", "Places", 0L, rig.track, PinBuilder.Pin.atPlace(pa[0], pa[1]), PinBuilder.Pin.atPlace(pb[0], pb[1]));
        final double gs2 = gateRoadS(rd, rp.stage, rp.stage.start), gf2 = gateRoadS(rd, rp.stage, rp.stage.finish);
        boolean noteStart = false, noteFinish = false;
        for (String n : rp.notes) {
            if (n.contains("start place is") && n.contains("from the road")) noteStart = true;
            if (n.contains("finish place is") && n.contains("from the road")) noteFinish = true;
        }
        Check.ok(String.format(Locale.US, "PN.B6 places 8 m left of the road at 600 m and 8 m right at 3200 m: the lines are on the road at %.2f m and %.2f m (within 1.5 m), the offsets are %.1f and %.1f m, the marks are the places as given, and both offsets are mentioned",
                gs2, gf2, rp.startOffsetM, rp.finishOffsetM),
                Math.abs(gs2 - 600.0) < 1.5 && Math.abs(gf2 - 3200.0) < 1.5 && Math.abs(rp.startOffsetM - 8.0) < 0.5 && Math.abs(rp.finishOffsetM - 8.0) < 0.5
                        && rp.stage.startMarkLat == pa[0] && rp.stage.startMarkLon == pa[1] && rp.stage.finishMarkLat == pb[0] && rp.stage.finishMarkLon == pb[1]
                        && rp.stage.geo.lat0 == pa[0] && rp.stage.geo.lon0 == pa[1] && noteStart && noteFinish && rp.startPasses == 1 && rp.finishPasses == 1);

        // PN.B7 a pin by position and the other by place
        final PinBuilder.Result rm = PinBuilder.build("st_mix", "Mixed", 0L, rig.track, PinBuilder.Pin.atPosition(sa), PinBuilder.Pin.atPlace(pb[0], pb[1]));
        Check.ok("PN.B7 one pin by position and the other by place work together",
                Math.abs(gateRoadS(rd, rm.stage, rm.stage.start) - 600.0) < 1.0 && Math.abs(gateRoadS(rd, rm.stage, rm.stage.finish) - 3200.0) < 1.5
                        && rm.stage.startMarkLat == llA[0] && rm.stage.finishMarkLat == pb[0]);

        // PN.B8 a GPS gap inside the stage is mentioned
        final List<Fix> gap = new ArrayList<Fix>();
        final double tGap = 110.0;
        for (Fix f : rig.fixes) if (!(f.t > tGap && f.t < tGap + 45.0)) gap.add(f);
        final Track tg = Track.of(gap);
        final PinBuilder.Result rg = PinBuilder.build("st_gap", "Gap", 0L, tg, PinBuilder.Pin.atPosition(trackS(tg, rd, 400.0)), PinBuilder.Pin.atPosition(trackS(tg, rd, 3200.0)));
        boolean gapNote = false;
        for (String n : rg.notes) if (n.contains("no fixes for") && n.contains("straight line")) gapNote = true;
        Check.ok("PN.B8 a 45 s hole in the GPS inside the stage is mentioned in the notes", gapNote, rg.notes.toString());

        // PN.B9 the stage works in the app's own live follower too: a StageRun fed the drive's fixes finishes with the same time as the scorer
        final StageRun live = new StageRun(back, false);
        for (Fix f : rig.fixes) live.onFix(f);
        Check.ok("PN.B9 a stage made from pins is followed fix by fix like any stage: StageRun reaches the finish", live.phase() == StageRun.POST || live.phase() == StageRun.FINISHED);
    }

    // ------------------------------------------------------------------ PN.B (timing against the exact time)

    static void timingTests() {
        final SynthRoad rd = SynthRoad.mountain();
        // the stage is made from one noisy recording; later drives are other recordings, at other paces, with other noise
        final List<Fix> first = drive(rd, -400.0, rd.total + 300.0, 18.0, 1.0, 40.0, 30.0, 2.5, 11);
        final Track tr = Track.of(first);
        final PinBuilder.Result r = PinBuilder.build("st_t", "Timing", 0L, tr, PinBuilder.Pin.atPosition(trackS(tr, rd, 600.0)), PinBuilder.Pin.atPosition(trackS(tr, rd, 3200.0)));
        final Stage st = StageFiles.parseStage(StageFiles.formatStage(r.stage));
        final double sS = gateRoadS(rd, st, st.start), sF = gateRoadS(rd, st, st.finish);

        // noise-free runs at three paces
        double worst0 = 0.0;
        for (double scale : new double[]{0.9, 1.0, 1.08}) {
            final SynthRoad.Drive dr = driveOf(rd, -400.0, rd.total + 300.0, 18.0, scale);
            final List<Fix> fx = rd.fixes(dr, 20.0, 0.0, 20.0 + dr.duration() + 20.0, 0.0, 0.7, 0.0, new Random(1));
            final List<RunResult> rr = Scorer.scoreAll(st, fx, "session_" + (int) (scale * 100) + ".csv", 0.0);
            if (rr.size() != 1 || !rr.get(0).finished) {
                worst0 = 99.0;
                continue;
            }
            worst0 = Math.max(worst0, Math.abs(rr.get(0).totalS - (dr.timeAt(sF) - dr.timeAt(sS))));
        }
        Check.ok(String.format(Locale.US, "PN.B10 three noise-free drives at 90 %%, 100 %% and 108 %% pace each give one finished run, the stage time within 30 ms of the exact time between the two lines (worst %.4f s)", worst0), worst0 < 0.03);

        // noisy runs: 150 drives, 2.5 m noise, paces 0.9 to 1.1
        final int n = 150;
        final double[] err = new double[n];
        int missing = 0, k = 0;
        for (int i = 0; i < n; i++) {
            final double scale = 0.9 + 0.2 * (i % 10) / 9.0;
            final SynthRoad.Drive dr = driveOf(rd, -400.0, rd.total + 300.0, 18.0, scale);
            final List<Fix> fx = rd.fixes(dr, 15.0, 0.0, 15.0 + dr.duration() + 20.0, 2.5, 0.7, 0.0, new Random(1000 + i));
            final List<RunResult> rr = Scorer.scoreAll(st, fx, "session_n" + i + ".csv", 0.0);
            if (rr.size() != 1 || !rr.get(0).finished) {
                missing++;
                continue;
            }
            err[k++] = rr.get(0).totalS - (dr.timeAt(sF) - dr.timeAt(sS));
        }
        final double[] ms = StageTests.meanStd(err, k);
        Check.ok(String.format(Locale.US, "PN.B11 %d noisy drives (2.5 m GPS noise, paces 90 to 110 %%) against a stage made from pins on one other noisy drive: all finish (%d missing), the error of the stage time is %+.3f s on average and %.3f s (1 sigma); the method page says about 0.17 s at 20 m/s", n, missing, ms[0], ms[1]),
                missing == 0 && Math.abs(ms[0]) < 0.06 && ms[1] < 0.30 && ms[1] > 0.04);
    }

    // ------------------------------------------------------------------ refusals

    static void refusalTests() {
        final Rig rig = new Rig(0.0, 3);
        final SynthRoad rd = rig.road;
        final Track tr = rig.track;
        final double s600 = trackS(tr, rd, 600.0), s3200 = trackS(tr, rd, 3200.0);

        final String wrong = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(s3200), PinBuilder.Pin.atPosition(s600)); } });
        Check.ok("PN.B12 the pins the wrong way round (finish before start) are refused as wrong_way", wrong.equals("wrong_way"), wrong);

        final double[] p600 = place(rd, 600.0, 0.0), p3200 = place(rd, 3200.0, 0.0);
        final String wrongPlaces = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPlace(p3200[0], p3200[1]), PinBuilder.Pin.atPlace(p600[0], p600[1])); } });
        Check.ok("PN.B13 places given the wrong way round on a drive made once are refused as wrong_way (this is the case of a stage that was driven the other way round)", wrongPlaces.equals("wrong_way"), wrongPlaces);

        final String nearStart = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(5.0), PinBuilder.Pin.atPosition(s3200)); } });
        final String nearStart19 = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(19.9), PinBuilder.Pin.atPosition(s3200)); } });
        final String nearEnd = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(s600), PinBuilder.Pin.atPosition(tr.length - 5.0)); } });
        final String nearEnd19 = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(s600), PinBuilder.Pin.atPosition(tr.length - 19.9)); } });
        final String neg = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(-50.0), PinBuilder.Pin.atPosition(s3200)); } });
        final String nan = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(Double.NaN), PinBuilder.Pin.atPosition(s3200)); } });
        final String okEdge = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(20.0), PinBuilder.Pin.atPosition(tr.length - 20.0)); } });
        Check.ok("PN.B14 a pin less than 20 m from either end of the recording is refused as near_end (5 m, 19.9 m, negative, not a number); exactly 20 m from each end is accepted",
                nearStart.equals("near_end") && nearStart19.equals("near_end") && nearEnd.equals("near_end") && nearEnd19.equals("near_end")
                        && neg.equals("near_end") && nan.equals("near_end") && okEdge.equals(""),
                nearStart + " " + nearStart19 + " " + nearEnd + " " + nearEnd19 + " " + neg + " " + nan + " edge:" + okEdge);

        final String tooShort = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(s600), PinBuilder.Pin.atPosition(s600 + 299.0)); } });
        final String justOk = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(s600), PinBuilder.Pin.atPosition(s600 + 301.0)); } });
        Check.ok("PN.B15 pins 299 m apart along the road are refused as too_short; 301 m apart are accepted", tooShort.equals("too_short") && justOk.equals(""), tooShort + " / " + justOk);

        // a loop: a circle of radius 80 m driven once
        final SynthRoad circle = new SynthRoad(3.5600, 101.6600, 40.0, new double[][]{{2 * Math.PI * 80.0, 80}}, 200, 200);
        final List<Fix> lf = drive(circle, -200.0, circle.total + 150.0, 15.0, 1.0, 20.0, 20.0, 0.0, 4);
        final Track lt = Track.of(lf);
        final String loop = category(new Runnable() { public void run() {
            PinBuilder.build("x", "x", 0L, lt, PinBuilder.Pin.atPosition(trackS(lt, circle, 0.0) + 40.0), PinBuilder.Pin.atPosition(trackS(lt, circle, 0.0) + 400.0)); } });
        Check.ok("PN.B16 two pins on a circle of 80 m radius, 360 m of road apart but 124 m apart in a straight line, are refused as a loop", loop.equals("loop"), loop);

        // a place that is not on the drive
        final double[] off = place(rd, 1500.0, 100.0);
        final String noStart = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPlace(off[0], off[1]), PinBuilder.Pin.atPosition(s3200)); } });
        final String noFinish = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(s600), PinBuilder.Pin.atPlace(off[0], off[1])); } });
        String msg = "";
        try {
            PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPlace(off[0], off[1]), PinBuilder.Pin.atPosition(s3200));
        } catch (PinBuilder.Refused e) {
            msg = e.getMessage();
        }
        Check.ok("PN.B17 a place 100 m from the road is refused as no_pass_start or no_pass_finish, and the sentence gives the distance",
                noStart.equals("no_pass_start") && noFinish.equals("no_pass_finish") && msg.contains("100 m"), noStart + " " + noFinish + " | " + msg);

        // a place 55 m from the road is accepted (the limit is 60), 65 m is not
        final double[] m55 = place(rd, 1500.0, 55.0), m65 = place(rd, 1500.0, 65.0);
        final String c55 = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPlace(m55[0], m55[1]), PinBuilder.Pin.atPosition(s3200)); } });
        final String c65 = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPlace(m65[0], m65[1]), PinBuilder.Pin.atPosition(s3200)); } });
        Check.ok("PN.B18 a place 55 m from the road is accepted, 65 m is refused", c55.equals("") && c65.equals("no_pass_start"), c55 + " / " + c65);

        // out and back: the first pair that makes a stage is used
        final double turn = 3000.0;
        final List<Fix> out = rd.fixesFn(new java.util.function.DoubleUnaryOperator() {
            public double applyAsDouble(double t) {
                final double s = -200.0 + 15.0 * t;
                return s <= turn ? s : Math.max(-200.0, turn - (s - turn));
            }
        }, 0.0, 2.0 * (turn + 200.0) / 15.0, 0.0, 0.7, new Random(9));
        final Track tb = Track.of(out);
        final double[] a1 = place(rd, 1000.0, 3.0), b1 = place(rd, 2500.0, 3.0);
        final PinBuilder.Result ob = PinBuilder.build("st_ob", "Out", 0L, tb, PinBuilder.Pin.atPlace(a1[0], a1[1]), PinBuilder.Pin.atPlace(b1[0], b1[1]));
        final double outStartS = ob.startS, outFinishS = ob.finishS;
        Check.ok(String.format(Locale.US, "PN.B19 a road driven out and back, a start place at 1000 m and a finish place at 2500 m, each passed twice: the first pair that makes a stage is used (start at %.0f m, finish at %.0f m of the track, the outward pass of both), and the notes say each place is passed twice", outStartS, outFinishS),
                ob.startPasses == 2 && ob.finishPasses == 2 && Math.abs(outFinishS - outStartS - 1500.0) < 30.0 && outStartS < 1400.0
                        && countContaining(ob.notes, "passes the start place 2 times") == 1 && countContaining(ob.notes, "passes the finish place 2 times") == 1,
                ob.notes.toString());
        final PinBuilder.Result back = PinBuilder.build("st_ob2", "Back", 0L, tb, PinBuilder.Pin.atPlace(b1[0], b1[1]), PinBuilder.Pin.atPlace(a1[0], a1[1]));
        Check.ok(String.format(Locale.US, "PN.B20 the same drive, start place at 2500 m and finish place at 1000 m: the first start pass is on the way out and the first finish pass after it is on the way back (stage %.0f m long: out to the turn and home to 1000 m)", back.stage.lengthM()),
                Math.abs(back.stage.lengthM() - 2500.0) < 60.0 && back.finishS > back.startS);
    }

    static int countContaining(List<String> l, String s) {
        int n = 0;
        for (String x : l) if (x.contains(s)) n++;
        return n;
    }


    // ------------------------------------------------------------------ PN.H

    /** The fixes of a drive with the ones between t0 and t1 (exclusive) taken out. */
    static List<Fix> withoutBetween(List<Fix> in, double t0, double t1) {
        final List<Fix> out = new ArrayList<Fix>();
        for (Fix f : in) if (!(f.t > t0 && f.t < t1)) out.add(f);
        return out;
    }

    /** The recording shifted in time by dt seconds. */
    static List<Fix> later(List<Fix> in, double dt) {
        final List<Fix> out = new ArrayList<Fix>();
        for (Fix f : in) out.add(new Fix(f.t + dt, f.lat, f.lon, f.speed, f.bearing, f.hAcc));
        return out;
    }

    /** Index of the fix after the longest wait for the next fix. */
    static int widest(List<Fix> fx) {
        int at = 1;
        for (int i = 1; i < fx.size(); i++) if (fx.get(i).t - fx.get(i - 1).t > fx.get(at).t - fx.get(at - 1).t) at = i;
        return at;
    }

    /** Two drives of one road in one recording: a from sA to sB, then (gapS seconds after the last fix of that) b from sC to sD. */
    static List<Fix> twoDrives(SynthRoad rd, double sA, double sB, double sC, double sD, double gapS) {
        final List<Fix> a = drive(rd, sA, sB, 18.0, 1.0, 20.0, 5.0, 0.0, 21);
        final List<Fix> b = drive(rd, sC, sD, 18.0, 1.0, 5.0, 20.0, 0.0, 22);
        final List<Fix> out = new ArrayList<Fix>(a);
        out.addAll(later(b, a.get(a.size() - 1).t + gapS - b.get(0).t));
        return out;
    }

    /** Fixes along a straight line east of the origin: one a second (or every dt seconds), from x0 to x1 metres at v m/s, the first at time t0. */
    static List<Fix> line(Geo g, double x0, double x1, double v, double dt, double t0) {
        final List<Fix> out = new ArrayList<Fix>();
        for (int i = 0; ; i++) {
            final double x = x0 + v * dt * i;
            if (x > x1 + 1e-9) break;
            out.add(new Fix(t0 + dt * i, g.lat(0.0), g.lon(x), v, 90.0, 5.0));
        }
        return out;
    }

    static String pins(final Track tr, final double s, final double f) {
        return category(new Runnable() { public void run() {
            PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(s), PinBuilder.Pin.atPosition(f)); } });
    }

    static void holeTests() {
        final SynthRoad rd = SynthRoad.mountain();
        final Geo g = new Geo(3.5700, 101.6700);

        // PN.H1 where the scorer cannot time a crossing: two fixes in a row more than 3 s apart. A straight road at 15 m/s with the
        // fixes missing for a while after 900 m (60 s); the start pin in the middle of the wait, the finish pin 1800 m on
        final double[] waits = {1.0, 2.9, 3.0, 3.1, 4.0, 13.0};
        final StringBuilder got = new StringBuilder();
        boolean right = true;
        for (double w : waits) {
            final List<Fix> fx = new ArrayList<Fix>(line(g, 0.0, 900.0, 15.0, 1.0, 0.0));
            fx.addAll(line(g, 900.0 + 15.0 * w, 2700.0, 15.0, 1.0, 60.0 + w));
            final Track tr = Track.of(fx);
            final double mid = 900.0 + 7.5 * w;
            final String start = pins(tr, mid, 2000.0), finish = pins(tr, 300.0, mid);
            final String want = w > 3.0 ? "hole" : "";
            final boolean ok = start.equals(want.length() == 0 ? "" : "hole_start") && finish.equals(want.length() == 0 ? "" : "hole_finish");
            got.append(String.format(Locale.US, " [%.1f s: %s / %s]", w, start.length() == 0 ? "ok" : start, finish.length() == 0 ? "ok" : finish));
            if (!ok) right = false;
        }
        Check.ok("PN.H1 a pin half way through a wait for the GPS: a wait up to 3 s (inclusive) is not a hole, more than 3 s is (hole_start for the start pin, hole_finish for the finish pin):" + got, right);

        // PN.H2 the edges of a 13 s hole (900 m to 1095 m): half a metre either side of it the pin is accepted, half a metre inside it is not
        final List<Fix> hf = new ArrayList<Fix>(line(g, 0.0, 900.0, 15.0, 1.0, 0.0));
        hf.addAll(line(g, 1095.0, 2700.0, 15.0, 1.0, 73.0));
        final Track th = Track.of(hf);
        final double[] at = {899.5, 900.5, 1094.5, 1095.5};
        final String[] sw = new String[at.length], fw = new String[at.length];
        for (int i = 0; i < at.length; i++) {
            sw[i] = pins(th, at[i], 2000.0);
            fw[i] = pins(th, 300.0, at[i]);
        }
        Check.ok("PN.H2 a 13 s hole between 900 m and 1095 m: a start pin at 899.5 m is accepted, at 900.5 m and at 1094.5 m is hole_start, at 1095.5 m is accepted; the finish pin the same with hole_finish:"
                        + " start " + java.util.Arrays.toString(sw) + " finish " + java.util.Arrays.toString(fw),
                sw[0].equals("") && sw[1].equals("hole_start") && sw[2].equals("hole_start") && sw[3].equals("")
                        && fw[0].equals("") && fw[1].equals("hole_finish") && fw[2].equals("hole_finish") && fw[3].equals(""));

        // PN.H3 the hole is judged on every fix, not only on those that make the track: a 90 s stand (fixes left out of the track because
        // the car is not moving) is a long wait between two vertices of the track, but the fixes kept coming every second
        final List<Fix> stand = new ArrayList<Fix>(drive(rd, -400.0, 1500.0, 18.0, 1.0, 20.0, 90.0, 0.0, 31));
        stand.addAll(later(drive(rd, 1500.0, rd.total + 300.0, 18.0, 1.0, 1.0, 20.0, 0.0, 32), stand.get(stand.size() - 1).t + 1.0 - 0.0));
        final Track ts = Track.of(stand);
        double vertexGap = 0.0;
        for (int i = 0; i + 1 < ts.n; i++) vertexGap = Math.max(vertexGap, ts.t[i + 1] - ts.t[i]);
        final double s1500 = trackS(ts, rd, 1500.0);
        final String standA = pins(ts, s1500 - 400.0, s1500 + 400.0), standB = pins(ts, s1500, s1500 + 900.0), standC = pins(ts, s1500 - 900.0, s1500);
        Check.ok(String.format(Locale.US, "PN.H3 a stand of 90 s in the middle of the drive is a gap of %.0f s between two vertices of the track, but the fixes kept coming every second, so it is not a hole: pins either side of the stand, and at it, are accepted", vertexGap),
                vertexGap > 60.0 && ts.holeAt(s1500) == 0.0 && standA.equals("") && standB.equals("") && standC.equals(""), standA + " / " + standB + " / " + standC);

        // PN.H4 on a road with bends: the hole of 13 s in the fixes at 130 s to 143 s
        final Track tm = Track.of(withoutBetween(drive(rd, -400.0, rd.total + 300.0, 18.0, 1.0, 40.0, 30.0, 0.0, 3), 130.0, 143.0));
        int vi = -1;
        for (int i = 0; i + 1 < tm.n; i++) if (tm.t[i + 1] - tm.t[i] > 10.0) vi = i;
        final double h0 = tm.route.s[vi], h1 = tm.route.s[vi + 1];
        final double[] ps = {h0 - 5.0, h0 + 5.0, (h0 + h1) / 2.0, h1 - 5.0, h1 + 5.0};
        final String[] cs = new String[ps.length], cf = new String[ps.length];
        for (int k = 0; k < ps.length; k++) {
            cs[k] = pins(tm, ps[k], h1 + 1500.0);
            cf[k] = pins(tm, Math.max(30.0, h0 - 1500.0), ps[k]);
        }
        Check.ok(String.format(Locale.US, "PN.H4 on the mountain road a 13 s hole in the fixes between %.0f m and %.0f m of the track: the start pin is accepted 5 m before it, is hole_start 5 m in, in the middle and 5 m before its end, and is accepted 5 m after it; the finish pin the same with hole_finish", h0, h1),
                cs[0].equals("") && cs[1].equals("hole_start") && cs[2].equals("hole_start") && cs[3].equals("hole_start") && cs[4].equals("")
                        && cf[0].equals("") && cf[1].equals("hole_finish") && cf[2].equals("hole_finish") && cf[3].equals("hole_finish") && cf[4].equals(""),
                java.util.Arrays.toString(cs) + " " + java.util.Arrays.toString(cf));
        String msgS = "", msgF = "";
        try {
            PinBuilder.build("x", "x", 0L, tm, PinBuilder.Pin.atPosition(ps[2]), PinBuilder.Pin.atPosition(h1 + 1500.0));
        } catch (PinBuilder.Refused e) {
            msgS = e.getMessage();
        }
        try {
            PinBuilder.build("x", "x", 0L, tm, PinBuilder.Pin.atPosition(Math.max(30.0, h0 - 1500.0)), PinBuilder.Pin.atPosition(ps[2]));
        } catch (PinBuilder.Refused e) {
            msgF = e.getMessage();
        }
        Check.ok("PN.H5 the sentence says how long the GPS was silent, which pin, and what to do",
                msgS.contains("13 s") && msgS.contains("start pin") && msgS.contains("Move the start pin")
                        && msgF.contains("13 s") && msgF.contains("finish pin") && msgF.contains("Move the finish pin"),
                msgS + " | " + msgF);

        // PN.H6 a hole inside the stage, away from both pins, is not a problem (the lines are crossed on good fixes)
        final PinBuilder.Result inside = PinBuilder.build("x", "x", 0L, tm, PinBuilder.Pin.atPosition(Math.max(30.0, h0 - 600.0)), PinBuilder.Pin.atPosition(h1 + 600.0));
        Check.ok("PN.H6 a 13 s hole between the pins, 600 m from each, still makes a stage", inside.stage.lengthM() > 1000.0);

        // PN.H7 a road driven twice; the first drive has a hole where the start place is. The pair of passes that can be timed is used
        final List<Fix> two = twoDrives(rd, -300.0, rd.total + 300.0, -300.0, rd.total + 300.0, 30.0);
        final Track tc = Track.of(two);
        final double[] pa = place(rd, 1200.0, 0.0), pb = place(rd, 2400.0, 0.0);
        final double tPass = tc.timeAt(tc.passes(pa[0], pa[1]).get(0).s);
        final Track td = Track.of(withoutBetween(two, tPass - 3.0, tPass + 3.0));
        final List<Track.Pass> passA = td.passes(pa[0], pa[1]);
        final PinBuilder.Result later = PinBuilder.build("x", "x", 0L, td, PinBuilder.Pin.atPlace(pa[0], pa[1]), PinBuilder.Pin.atPlace(pb[0], pb[1]));
        Check.ok(String.format(Locale.US, "PN.H7 a road driven twice, the first time with 6 s of the GPS missing at the start place: the start place has %d passes, the first is in a hole (%.0f s) and is skipped, and the stage is made from the second drive (start pin at %.0f m of the track, the second pass is at %.0f m)",
                        passA.size(), td.holeAt(passA.get(0).s), later.startS, passA.get(1).s),
                passA.size() == 2 && td.holeAt(passA.get(0).s) > 3.0 && td.holeAt(passA.get(1).s) == 0.0 && Math.abs(later.startS - passA.get(1).s) < 1e-9
                        && later.finishS > later.startS && later.startPasses == 2 && later.finishPasses == 2);
    }

    static void breakTests() {
        final SynthRoad rd = SynthRoad.mountain();
        final Geo g = new Geo(3.5700, 101.6700);

        // PN.H8 two drives in one recording on a straight road, 1800 m of stage across the join. A break is more than 300 s without a fix
        // AND the car more than 100 m from where it was: 300 s is not a break, nor is 100 m
        final double[][] cases = {          // seconds without a fix, metres the car moved meanwhile
            {400.0, 600.0}, {300.5, 600.0}, {300.0, 600.0}, {299.5, 600.0}, {400.0, 100.5}, {400.0, 99.5}, {400.0, 0.0}, {5000.0, 1000.0}};
        final String[] want = {"break", "break", "", "", "break", "", "", "break"};
        final StringBuilder bg = new StringBuilder();
        boolean allRight = true;
        for (int k = 0; k < cases.length; k++) {
            final double wait = cases[k][0], jump = cases[k][1];
            final List<Fix> fx = new ArrayList<Fix>(line(g, 0.0, 900.0, 15.0, 1.0, 0.0));           // the first drive ends at 900 m, at 60 s
            fx.addAll(line(g, 900.0 + jump, 3000.0 + jump, 15.0, 1.0, 60.0 + wait));                  // the second begins `jump` m on, `wait` s later
            final Track tr = Track.of(fx);
            final String c = pins(tr, 300.0, 2000.0);
            bg.append(String.format(Locale.US, " [%.1f s, %.1f m: %s]", wait, jump, c.length() == 0 ? "ok" : c));
            if (!c.equals(want[k])) allRight = false;
        }
        Check.ok("PN.H8 a stage across two drives joined in one recording: refused as a break when the gap is more than 300 s and the car is more than 100 m apart across it; at exactly 300 s, or 99.5 m, or still, accepted:" + bg, allRight);

        // PN.H9 pins on one side of the break are fine; the sentence for a pair across it says how long and how far, and what to do
        final List<Fix> fx = new ArrayList<Fix>(line(g, 0.0, 900.0, 15.0, 1.0, 0.0));
        fx.addAll(line(g, 1500.0, 3600.0, 15.0, 1.0, 460.0));
        final Track tj = Track.of(fx);
        final String before = pins(tj, 200.0, 800.0), after = pins(tj, 1700.0, 3000.0);
        String msg = "";
        try {
            PinBuilder.build("x", "x", 0L, tj, PinBuilder.Pin.atPosition(500.0), PinBuilder.Pin.atPosition(2500.0));
        } catch (PinBuilder.Refused e) {
            msg = e.getMessage();
        }
        Check.ok("PN.H9 two pins on the first drive, or two on the second, are fine; across a break of 400 s with the car 600 m apart across it the sentence says so and that a stage is one continuous drive",
                before.equals("") && after.equals("") && msg.contains("400 s") && msg.contains("600 m") && msg.contains("one continuous drive"), before + " / " + after + " / " + msg);

        // PN.H10 the break is measured on the fixes either side of the hole, whatever the car did in between: on the mountain road two drives
        // joined after 1500 m, the second from 2100 m; the straight distance across (bends) is what counts, and the sentence quotes it
        final List<Fix> two = twoDrives(rd, -400.0, 1500.0, 2100.0, rd.total + 300.0, 400.0);
        final Track t2 = Track.of(two);
        final int w = widest(two);
        final Fix p = two.get(w - 1), q = two.get(w);
        final double expect = Math.hypot(t2.geo.x(q.lon) - t2.geo.x(p.lon), t2.geo.y(q.lat) - t2.geo.y(p.lat));
        final double[] pm = place(rd, 2600.0, 0.0);
        String msg2 = "";
        try {
            PinBuilder.build("x", "x", 0L, t2, PinBuilder.Pin.atPosition(trackS(t2, rd, 800.0)), PinBuilder.Pin.atPosition(t2.passes(pm[0], pm[1]).get(0).s));
        } catch (PinBuilder.Refused e) {
            msg2 = e.getMessage();
        }
        Check.ok(String.format(Locale.US, "PN.H10 on the mountain road, two drives with 400 s between and the road 600 m on: the sentence quotes the straight distance between the fixes either side of the hole (%s, from the fixes themselves)", StageText.dist(expect)),
                expect > 100.0 && expect < 600.0 && msg2.contains("400 s") && msg2.contains(StageText.dist(expect)), msg2);

        // PN.H11 pins by place: the first pair of passes that does not cross the break is used
        // (a) the start place is passed in both drives, the finish place only in the second: the first drive's start pass crosses the break,
        // the second drive's start pass is used
        final double[] pm1 = place(rd, 800.0, 0.0), pm2 = place(rd, 2600.0, 0.0);
        final List<Fix> d1 = twoDrives(rd, -400.0, 1500.0, -400.0, rd.total + 300.0, 400.0);
        final Track t3 = Track.of(d1);
        final List<Track.Pass> sp = t3.passes(pm1[0], pm1[1]);
        final PinBuilder.Result ra = PinBuilder.build("x", "x", 0L, t3, PinBuilder.Pin.atPlace(pm1[0], pm1[1]), PinBuilder.Pin.atPlace(pm2[0], pm2[1]));
        Check.ok(String.format(Locale.US, "PN.H11 a start place passed in both drives and a finish place only in the second, with a break between them: the first start pass (%.0f m) would cross the break, so the stage uses the second (start pin at %.0f m, finish pin at %.0f m)",
                        sp.get(0).s, ra.startS, ra.finishS),
                sp.size() == 2 && ra.startPasses == 2 && ra.finishPasses == 1 && Math.abs(ra.startS - sp.get(1).s) < 1e-9 && ra.finishS > ra.startS);
        // (b) the only start pass is in the first drive and the only finish pass in the second: refused as a break
        final List<Fix> d2 = twoDrives(rd, -400.0, 1500.0, 2000.0, rd.total + 300.0, 400.0);
        final Track t4 = Track.of(d2);
        final String cb = category(new Runnable() { public void run() {
            PinBuilder.build("x", "x", 0L, t4, PinBuilder.Pin.atPlace(pm1[0], pm1[1]), PinBuilder.Pin.atPlace(pm2[0], pm2[1])); } });
        Check.ok("PN.H12 a start place passed only in the first drive and a finish place only in the second, with a break between them: refused as a break", cb.equals("break"), cb);
    }

    // ------------------------------------------------------------------ PN.G

    /** A road with a run of switchbacks: A, B, C legs of legLen metres, joined by half-circles of radius r (so the legs are 2 r apart). */
    static SynthRoad stack(double leadStraight, double leg, double r, double tail) {
        return new SynthRoad(3.5700, 101.6700, 90.0, new double[][]{
                {leadStraight, 0}, {leg, 0}, {Math.PI * r, r}, {leg, 0}, {Math.PI * r, -r}, {leg, 0}, {tail, 0}}, 300, 300);
    }

    static void guardTests() {
        // the stack at the END of a long stage: the leg two back runs the same way as the last one and, if it is within 35 m, crosses the finish line
        final double[] radii = {6.0, 8.0, 10.0, 15.0};
        final String[] finishGot = new String[radii.length];
        final String[] startGot = new String[radii.length];
        for (int i = 0; i < radii.length; i++) {
            final double r = radii[i];
            final SynthRoad rd = stack(1500.0, 200.0, r, 200.0);
            final double tot = rd.total;
            final List<Fix> fx = drive(rd, -250.0, tot + 150.0, 12.0, 1.0, 20.0, 20.0, 0.0, 6);
            final Track tr = Track.of(fx);
            final double sStart = trackS(tr, rd, 50.0);
            final double sFin = trackS(tr, rd, 1500.0 + 200.0 + Math.PI * r + 200.0 + Math.PI * r + 100.0);        // the middle of the third leg
            finishGot[i] = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(sStart), PinBuilder.Pin.atPosition(sFin)); } });

            // the stack at the START: the start line is in the middle of the first leg, the leg two on (C) runs the same way and crosses it
            final SynthRoad rs = stack(300.0, 100.0, r, 1500.0);
            final List<Fix> fs = drive(rs, -250.0, rs.total + 150.0, 12.0, 1.0, 20.0, 20.0, 0.0, 7);
            final Track ts = Track.of(fs);
            final double sa = trackS(ts, rs, 300.0 + 50.0), sb = trackS(ts, rs, rs.total - 150.0);
            startGot[i] = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, ts, PinBuilder.Pin.atPosition(sa), PinBuilder.Pin.atPosition(sb)); } });
        }
        Check.ok("PN.G1 switchbacks at the end of a 1.9 km stage, the finish line in the middle of the last leg: legs 12 and 16 m apart are refused as guard_finish, legs 20 and 30 m apart are accepted (the leg two back is 24, 32, 40, 60 m away; the check looks 35 m either side)",
                finishGot[0].equals("guard_finish") && finishGot[1].equals("guard_finish") && finishGot[2].equals("") && finishGot[3].equals(""),
                String.join(",", finishGot));
        Check.ok("PN.G2 switchbacks right after the start, the start line in the middle of the first leg: legs 12 and 16 m apart are refused as guard_start, legs 20 and 30 m apart are accepted",
                startGot[0].equals("guard_start") && startGot[1].equals("guard_start") && startGot[2].equals("") && startGot[3].equals(""),
                String.join(",", startGot));

        // the refusal says where
        final SynthRoad rd = stack(1500.0, 200.0, 8.0, 200.0);
        final List<Fix> fx = drive(rd, -250.0, rd.total + 150.0, 12.0, 1.0, 20.0, 20.0, 0.0, 6);
        final Track tr = Track.of(fx);
        String msg = "";
        try {
            PinBuilder.build("x", "x", 0L, tr, PinBuilder.Pin.atPosition(trackS(tr, rd, 50.0)),
                    PinBuilder.Pin.atPosition(trackS(tr, rd, 1500.0 + 200.0 + Math.PI * 8.0 + 200.0 + Math.PI * 8.0 + 100.0)));
        } catch (PinBuilder.Refused e) {
            msg = e.getMessage();
        }
        Check.ok("PN.G3 the sentence says which line, how far into the stage, and what to do", msg.contains("finish line") && msg.contains("into the stage") && msg.contains("Move the finish pin"), msg);

        // a straight road with a long hairpin-free stage is never refused by the guard (no false alarm on the ordinary road)
        final SynthRoad m = SynthRoad.mountain();
        final List<Fix> fm = drive(m, -400.0, m.total + 300.0, 18.0, 1.0, 20.0, 20.0, 0.0, 8);
        final Track tm = Track.of(fm);
        int refused = 0, loops = 0, tried = 0;
        for (double a = 100.0; a < 1500.0; a += 100.0) {
            for (double b = a + 400.0; b < 4400.0; b += 300.0) {
                tried++;
                final double sa = trackS(tm, m, a), sb = trackS(tm, m, b);
                final String c = category(new Runnable() { public void run() { PinBuilder.build("x", "x", 0L, tm, PinBuilder.Pin.atPosition(sa), PinBuilder.Pin.atPosition(sb)); } });
                if (c.startsWith("guard")) refused++;
                if (c.equals("loop")) loops++;
            }
        }
        Check.ok("PN.G4 on the mountain road (hairpins 30 m wide at its worst) " + tried + " different pairs of pins: the guard refuses none of them (it raises no false alarm on an ordinary road); " + loops + " pair"
                + (loops == 1 ? " is" : "s are") + " refused as a loop, which is right: the road there doubles back, so the pins are under 150 m apart in a straight line", refused == 0 && loops <= 3, refused + " guard refusals, " + loops + " loops of " + tried);
    }

    // ------------------------------------------------------------------ PN.R (real recording)

    /**
     * {aLat, aLon, bLat, bLon}: where the real car was 5 s and 80 s after the third pass of the Sepang lap line (the pseudo-stage of
     * ST.RL2), each moved offsetM to the left of its course.
     */
    static double[] realStretchPlaces(LogReader.Log log, double offsetM) {
        final Geo geo = new Geo(LapTimer.SEPANG_LAT, LapTimer.SEPANG_LON);
        final Gate line = new Gate(0.0, 0.0, LapTimer.SEPANG_BEARING_DEG, LapTimer.HALF_WIDTH_M);
        final List<Double> passes = new ArrayList<Double>();
        Pt prev = null;
        for (Fix f : log.fixes) {
            if (prev != null && !(f.t > prev.t)) continue;
            final Pt cur = Pt.make(geo, f, prev);
            if (prev != null && cur.t - prev.t <= LapTimer.MAX_FIX_GAP_S) {
                final double tc = line.crossing(prev, cur, LapTimer.MIN_SPEED_MS, 0.0);
                if (!Double.isNaN(tc) && (passes.isEmpty() || tc - passes.get(passes.size() - 1) >= LapTimer.MIN_LAP_S)) passes.add(tc);
            }
            prev = cur;
        }
        final double tA = passes.get(2) + 5.0, tB = passes.get(2) + 80.0;
        Fix fa = null, fb = null;
        for (Fix f : log.fixes) {
            if (fa == null && f.t >= tA) fa = f;
            if (fb == null && f.t >= tB) fb = f;
        }
        final double hA = Math.toRadians(fa.bearing >= 0 ? fa.bearing : 0.0), hB = Math.toRadians(fb.bearing >= 0 ? fb.bearing : 0.0);
        return new double[]{
                fa.lat + Math.toDegrees(offsetM * Math.sin(hA) / Geo.R), fa.lon - Math.toDegrees(offsetM * Math.cos(hA) / (Geo.R * Math.cos(Math.toRadians(fa.lat)))),
                fb.lat + Math.toDegrees(offsetM * Math.sin(hB) / Geo.R), fb.lon - Math.toDegrees(offsetM * Math.cos(hB) / (Geo.R * Math.cos(Math.toRadians(fb.lat))))};
    }

    static void realTests() throws Exception {
        final String path = System.getProperty("real.log");
        if (path == null || !new File(path).isFile()) {
            System.out.println("(PN.R skipped: pass -Dreal.log=<session csv> to run the pin builder on the real Sepang recording)");
            return;
        }
        System.out.println("\n----- PN.R: the pin builder on the real Sepang recording -----");
        final LogReader.Log log = LogReader.read(new File(path));
        final Track tr = Track.of(log.fixes);
        Check.ok(String.format(Locale.US, "PN.R1 the real 44-minute recording becomes a track of %d vertices and %.1f km (about 17 laps' worth of driving at 5.5 km a lap, with the pit lane)", tr.n, tr.length / 1000.0),
                tr.n > 1500 && tr.length > 80000.0 && tr.length < 110000.0);

        // a stretch of lap 3, as in ST.RL2: 5 s to 80 s after the line; the places are where the car was at those two moments, each moved 8 m sideways
        final double[] pl = realStretchPlaces(log, 8.0);
        final double aLat = pl[0], aLon = pl[1], bLat = pl[2], bLon = pl[3];
        final PinBuilder.Result r = PinBuilder.build("st_sepang", "Sepang stretch", 0L, tr, PinBuilder.Pin.atPlace(aLat, aLon), PinBuilder.Pin.atPlace(bLat, bLon));
        final Stage st = StageFiles.parseStage(StageFiles.formatStage(r.stage));
        Check.ok(String.format(Locale.US, "PN.R2 two places on the circuit, 8 m off the line the car took, become a stage of %.0f m (the 75 s stretch of lap 3 at about 20 m/s is about 1.5 km); the car passes them in every lap, and the first pass that makes a stage was used (%d and %d passes found)",
                st.lengthM(), r.startPasses, r.finishPasses),
                st.lengthM() > 900.0 && st.lengthM() < 4000.0 && r.startPasses >= 15 && r.finishPasses >= 15 && r.startOffsetM > 2.0 && r.startOffsetM < 14.0);

        final List<RunResult> runs = Scorer.scoreAll(st, log.fixes, "session_real.csv", log.startUtcMs);
        int finished = 0;
        double lo = 1e9, hi = 0.0;
        for (RunResult x : runs) {
            if (!x.finished) continue;
            finished++;
            lo = Math.min(lo, x.totalS);
            hi = Math.max(hi, x.totalS);
        }
        Check.ok(String.format(Locale.US, "PN.R3 scoring the whole recording against it finds %d runs, %d of them finished, with times from %.1f s to %.1f s (the stretch is the same every lap; the spread is the pace of the laps, the pit lane and the in-lap)", runs.size(), finished, lo, hi),
                runs.size() >= 15 && runs.size() <= 18 && finished >= 15 && lo > 40.0 && hi < 140.0);

        // pins by position at the same two points give the same stage as the places (apart from the places' offset)
        final PinBuilder.Result r2 = PinBuilder.build("st_sepang2", "Sepang stretch 2", 0L, tr, PinBuilder.Pin.atPosition(r.startS), PinBuilder.Pin.atPosition(r.finishS));
        final double dStart = Geo.distance(r.stage.geo.lat(r.stage.start.cy), r.stage.geo.lon(r.stage.start.cx), r2.stage.geo.lat(r2.stage.start.cy), r2.stage.geo.lon(r2.stage.start.cx));
        final double dFinish = Geo.distance(r.stage.geo.lat(r.stage.finish.cy), r.stage.geo.lon(r.stage.finish.cx), r2.stage.geo.lat(r2.stage.finish.cy), r2.stage.geo.lon(r2.stage.finish.cx));
        Check.ok(String.format(Locale.US, "PN.R4 pins by position at the places' pass positions give the same lines (%.4f m and %.4f m apart) and the same route length (%.4f m apart)",
                dStart, dFinish, Math.abs(r2.stage.lengthM() - r.stage.lengthM())),
                dStart < 0.01 && dFinish < 0.01 && Math.abs(r2.stage.lengthM() - r.stage.lengthM()) < 0.01);
    }
}
