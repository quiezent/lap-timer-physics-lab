# What the pin test files do not hold a second implementation to (release 1.5)

The test files in this folder are made by the Android app's Java code, and `analysis/pin_compare.py` (a second implementation of section 11 of [`docs/stage-algorithm.md`](../../../docs/stage-algorithm.md), written from the page alone) must reproduce them: it does, in every one of 391 texts, 30 recordings, 60 places, 115 pairs of pins, 62 stage files and 62 first runs.
That a second implementation agrees says little if the files never put a rule to the test, so its author **broke it in 422 one-line ways** (381 different ones, 41 being the same change written twice) and ran each broken copy through the six groups of these files only. The files noticed 279 of the 422. **143 survived**, on 129 lines of the code. I re-ran the author's script myself on 14 Oct 2026 and got the same totals (422 tried, 279 detected, 143 survived, none that could not be built); the script is a few hundred lines of one-line edits tied to the exact text of `pin_compare.py`, and it is not in this repository. This page is that list: it is the to-do list for the next fixtures, and a warning to anyone who writes a third implementation that the files alone will not catch these mistakes.

Why this matters for the app: the Java tests (`PN.*` in `android/tests`) exercise most of these rules directly (the 3.0 s hole limit, the edges of a hole to half a metre, the 300 s and 100 m of a break, the 20 m and 300 m limits, the coordinate texts), but only against my reading of the page. A rule listed here is one that **no second implementation is yet held to**.

**The kinds.** `gap`: a rule of the page that a real input can show and no fixture exercises (109 of the 143 mutations; 96 lines). `edge`: it would take a computed floating-point number landing exactly on a constant, which is not portable between languages, so no fixture can hold it (14). `same`: the change alters no behaviour that can be reached, I believe (12; 11 lines). `note`: a sentence of section 11.7, which is in no file by design (8).
A star after an id means the second implementation's own checks do not catch it either (19 of the 143 are missed by both). `A=B` means the same change was written twice under two ids.


### 11.1 / 11.2 track, holes, snapping

| id | kind | the change that no fixture notices |
|---|---|---|
| T09\* | edge | vertex rule: a fix exactly 3.0 m from the last vertex is dropped (< becomes <=) |
| H01 | gap | a gap of exactly 3.0 s is a hole (> 3.0 becomes >= 3.0) |
| H02 | gap | hole limit 2.5 s instead of 3.0 |
| H03 | gap | hole limit 3.5 s |
| H04 | gap | hole limit 2.0 s |
| H05 | gap | hole limit 5.0 s |
| H09 | gap | hole distance is the east-west part only, \|dx\|, not hypot(dx, dy) (H08, \|dy\| only, is caught) |
| H10 | gap | hole distance taken between the vertices either side of the hole, not between its two fixes |
| TA4 | same | timeAt does not clamp d into [0, L] (unreachable: rule 2 keeps both pins inside) |
| P01\* | edge | snap limit strict (d < 60 instead of d <= 60) |
| P04\* | edge | on an exact tie the LAST segment is a pass's best, not the first (d <= best) |
| P05\* | edge | pass window strict (offset < smallest + 10 instead of <=) |

### 11.3 pairs and rules

| id | kind | the change that no fixture notices |
|---|---|---|
| R14 | gap | pairs tried with b as the outer loop (b then a), not a then b |
| R16 | gap | refusal category taken from the LAST pair with b > a, not the first |
| PL08 | gap | a guard refusal falls through to the next pair that meets rules 2-7 (guard tried on other pairs) |
| R20\* | edge | rule 1 as b > a + 1e-6 |
| IN1 | gap | an infinite position counts as a number (only NaN is near_end) |
| IN2 | gap | +Infinity alone counts as a number |
| IN3 | gap | -Infinity alone counts as a number |
| R02 | edge | rule 2: b < L - 20 (strict) |
| RX5 | gap | rule 4: minimum straight distance 149 m |
| RX6 | gap | rule 4: minimum straight distance 151 m |
| R08\* | edge | rule 4: straight distance > 150 (strict) |
| OR03 | gap | rule 6 checked before rule 5 (hole_finish before hole_start) |
| OR04 | gap | rules 5, 6, 7 checked before rule 4 (order 2 3 5 6 7 4) |
| OR05 | gap | rules 5, 6, 7 checked before rules 3 and 4 (order 2 5 6 7 3 4) |
| OR06 | gap | rules 5, 6, 7 checked before rules 2, 3, 4 (order 5 6 7 2 3 4) |
| OR11 | gap | rule 5 checked before rule 4 only (order 2 3 5 4 6 7) |
| HS1 | gap | rule 5, ends: the start of the hole does not count (t0 < timeAt(a)) |
| HS2 | gap | rule 5, ends: the end of the hole does not count (timeAt(a) < t1) |
| HS3 | gap | rule 5, ends: neither end counts |
| HF1 | gap | rule 6, ends: the start of the hole does not count |
| HF2 | gap | rule 6, ends: the end of the hole does not count |
| HF3 | gap | rule 6, ends: neither end counts |
| BR02 | edge | rule 7: a hole of exactly 100 m is a break (> 100 becomes >= 100) |
| BR13\* | same | rule 7: t1 >= timeAt(a) instead of > |
| BR14\* | same | rule 7: t0 <= timeAt(b) instead of < |
| BR18\* | same | rule 7: the hole must lie inside the pins (t0 > timeAt(a) and t1 < timeAt(b)) |

### 11.5 route

| id | kind | the change that no fixture notices |
|---|---|---|
| S01 | gap | route margin 0 instead of 0.01: a vertex within 1 cm after the start pin (or before the finish pin) stays in the route |

### 11.6 guard

| id | kind | the change that no fixture notices |
|---|---|---|
| G05 | edge | \|across\| must be < 35 (strict) |
| G06 | edge | start window 30 <= u |
| G07 | edge | start window u < 300 |
| G08 | gap | start window, lower limit 20 |
| G09 | gap | start window, lower limit 40 |
| G10 | gap | start window, upper limit 250 |
| G11 | gap | start window, upper limit 400 |
| G12 | gap | start window, no lower limit |
| G13 | edge | finish window 0.7 R < u |
| G14 | edge | finish window u <= R - 30 |
| G15 | gap | finish window, fraction 0.6 instead of 0.7 |
| G17 | gap | finish window, end margin 20 |
| G18 | gap | finish window, end margin 40 |
| G19 | gap | finish window, no end margin |
| G23 | edge | a crossing needs q0 <= 0 < q1 |
| G24 | gap | finish gate checked before the start gate |
| G25\* | same | width tested by the distance from the gate centre (hypot) instead of \|across\| |
| G26 | gap | finish window: only the first forward crossing is looked at |
| G27 | gap | start window: only the first forward crossing is looked at |
| G20 | gap | the guard is judged on the stage read back from its file, not on the numbers as built |
| S11 | gap | the guard's route distances are computed from the 3-decimal rounded points |

### 11.7 notes (in no file)

| id | kind | the change that no fixture notices |
|---|---|---|
| N1\* | note | offset note from 5 m (>= 5) |
| N2 | note | gap: 20 s OR 100 m (or instead of and) |
| N3 | note | gap: exactly 20 s counts (>=) |
| N4\* | note | gap: exactly 100 m counts (>=) |
| N5 | note | "which pass" also said when there is a single pass |
| N6 | note | gaps: only segments wholly between the pins (one cut by a pin is not reported) |
| N7 | note | gaps judged on the holes (all fixes) instead of the vertex segments |
| N8 | note | gap position given along the track, not into the stage |

### 11.8 white space

| id | kind | the change that no fixture notices |
|---|---|---|
| WD_2000 | gap | U+2000 dropped from the white-space set |
| WD_2001 | gap | U+2001 dropped |
| WD_2002 | gap | U+2002 dropped |
| WD_2004 | gap | U+2004 dropped |
| WD_2005 | gap | U+2005 dropped |
| WD_2006 | gap | U+2006 dropped |
| WD_2007 | gap | U+2007 dropped |
| WD_2008 | gap | U+2008 dropped |
| WA_200C | gap | U+200C (zero width non-joiner) added to the set |
| WA_200D | gap | U+200D (zero width joiner) added |
| WA_00AD | gap | U+00AD (soft hyphen) added |
| WA_2800 | gap | U+2800 (braille blank) added |
| TRIM_JAVA=WT1 | gap | trim removes every character up to U+0020 (as Java's String.trim), not the 28 |

### 11.8 length

| id | kind | the change that no fixture notices |
|---|---|---|
| C03=LEN2 | gap | length counted in code points, not UTF-16 units |
| LEN6 | gap | length counted in UTF-8 bytes |

### 11.8 case

| id | kind | the change that no fixture notices |
|---|---|---|
| CF1\* | same | link detection with str.lower() |
| CF2 | gap | link detection with str.casefold() (U+017F in /maps) |
| CF3 | gap | link detection with upper() then lower() |
| CF4\* | same | goo.gl/ test with casefold |
| CF5=U_ASCII_OFF\* | same | link patterns without re.ASCII (Unicode case folding) |
| CF6\* | same | geo: prefix with casefold |
| CF7=N_UNIUP | gap | letters N S E W matched with Unicode case mapping (U+017F is an S); two spellings of one change |
| CF9 | gap | the goo.gl/ test is case-sensitive |
| CF16 | gap | !3d / !4d case-sensitive (the other patterns blind) |

### 11.8 short links

| id | kind | the change that no fixture notices |
|---|---|---|
| GO2 | gap | maps.app.goo.gl (without the slash) is also a short link |
| GO3 | gap | goo.gl without the slash is a short link |
| GO6 | gap | goo.gl/ refuses any text, link or not |

### 11.8 map links

| id | kind | the change that no fixture notices |
|---|---|---|
| LM3 | gap | rightmost match for the patterns other than the query pattern |
| LM4 | gap | query names tried one after the other (q, ll, query, center), not by leftmost position |
| LM5=U_RANGE_FALL | gap | an out-of-range first match falls through to the next pattern |
| LM6 | gap | an out-of-range first match falls through to a later match of the same pattern |
| LK3 | gap | link numbers accept U+2212 as a minus |
| LK6 | gap | the query pattern allows white space round the comma |
| LK7=U_COMMA_ALL | gap | %2C accepted as the comma in every pattern, not only the query pattern |
| LK9=U_QSEP | gap | the query may also follow # or ; |
| LK15 | gap | the query name needs no [?&] before it |
| LK16 | gap | www. anywhere in the text makes a link (not only at the start) |
| LK17=U_MAPSBEFORE | gap | /maps may come before google. |
| LK18 | gap | google. must be followed by com/maps (google.co.uk/maps is not a link) |
| U_NOWWW | gap | www. does not make a link |
| U_ORDER_q_place | gap | query and /place/ patterns swapped in priority |
| LB1 | gap | a link number must not be followed by a digit or a dot |
| LB2 | gap | a link number must not be followed by a letter either |
| E_BEFORE_LINK=GE11 | gap | geo: tested before the map link |
| GE7 | gap | geo: cut at the LAST ? or ; instead of the first |

### 11.8 plain text

| id | kind | the change that no fixture notices |
|---|---|---|
| PT2 | gap | degrees whole by text ('3.0' is not whole) |
| PT3 | gap | minutes whole by text ('8.0' is not whole) |
| A_U2212=PT14 | gap | a number followed by U+2212 is read |
| K_RING | gap | U+02DA is not a degree mark |
| K_ORD | gap | U+00BA is not a degree mark |
| K_RSQ | gap | U+2019 is not a minute mark |
| K_RDQ | gap | U+201D is not a seconds mark |
| K_PLAIN_ONLY_DEG | gap | a pair without letters refuses minute and second marks (degree marks only) |
| K_ADJ | gap | a mark is skipped only straight after a number or another mark |
| K_LONE=PT26 | gap | a mark before the first number or letter is refused |
| N_M60=PT22 | gap | minutes may be 60 |
| N_COUNT4 | gap | up to 4 numbers per coordinate |
| N_MODE\* | same | start/end mode decided by the last token |
| N_LET3\* | same | more than two letters accepted, the extra ignored |
| N_NSEW=PT25 | gap | two letters of the same axis accepted |
| N_ZERO2=PT18 | gap | a zero latitude OR longitude refused (instead of (0, 0)) |

## How to make a fixture for each gap

These recipes come from the second implementation's author, who tried each against their own code. A new fixture is made the usual way: add the recording and the case to `android/tests/test/app/tracklog/PinFixtures.java`, regenerate (the command is in [`../README.md`](../README.md)), look at the diff, and run `python analysis/pin_compare.py selftest`. If the Java and the Python disagree on a new case, the page was vague and needs a sentence.

- **The 3.0 s limit (H01 to H05).** The same recording with a gap of 2.9, 3.0 and 3.1 s under a pin: `ok`, `ok`, `hole_start`. Use whole-second times, where the difference is exact.
- **Whether the ends of a hole count (HS1 to HS3, HF1 to HF3).** Put the start pin (then the finish pin) as an `at:` place exactly on the fix before and after a hole, with fix coordinates of at most 7 decimals so that the text of the pin is the fix. The result then rides on the last bit of the position, so add this one only if the Java and the Python agree.
- **The distance across a hole (H09, H10).** Holes whose two fixes are apart north-south or diagonally, 99 and 101 m (for a break); a hole whose fixes are not vertices.
- **The order of the rules (OR03 to OR11).** Pins that fail two rules at once: start and finish both in a hole; a hole and `too_short`; a hole and `near_end` (a hole over the first vertices, the start pin at 10 m); a hole and `loop` (a hairpin road with a 5 s hole around the start pin, the pins 300 m apart but under 150 m in a straight line).
- **An infinite position (IN1 to IN3).** `pos:Infinity` as the start with a finite finish; a finite start with `pos:-Infinity` as the finish.
- **The order of the pairs (R14, R16, PL08).** A road with two passes at each place: a start place with passes at x = +1 and x = -1, a finish place 151 m east with passes at x = 150 and 152. The pair (a1, b1) is a loop (149 m), so a-then-b gives (a1, b2) and b-then-a gives (a2, b1). For R16 and PL08, two pairs failing different rules; a first valid pair that fails the guard beside a second that would pass.
- **The straight distance of a loop (RX5, RX6).** Pins 300 m apart on the road with a straight-line distance of 149.9 m and 150.1 m.
- **The route margin (S01).** A `pos:` pin 5 mm before a vertex.
- **The windows of the guard (G08 to G19, G24, G26, G27).** Roundabouts after the start pin with r = 4, 6, 40 and 50 m (the first crossing at about u = 24, 36, 239 and 298 m); at the finish pin with r = 4, 6, 3.2 and 55.7 m (crossing at R - 24, R - 36, R - 20 and 0.61 R); two laps of r = 4 m after the start (G27); a big loop at 0.56 R and a small one at R - 36 at the finish (G26, G18); roundabouts at both ends, so that the start must be named first (G24).
- **The guard on the numbers as built (G20, S11).** Three switchback legs 35.000045 m apart: `ok` as built, `guard_start` if judged on the rounded file.
- **The reader of 11.8 (all the `11.8` rows).** Every non-starred row has a text and an expected result in `_coords_table()` in `analysis/pin_compare.py`; copy them into the texts of the fixtures (in `PinTests.READ_TEXT` and `READ_REFUSED` or a new list read by `PinFixtures.coordsList`). Do not forget U+2007, which is one of the survivors. The Java reader already gives the same answer on all 241 rows of that table (0 differences, checked 14 Oct 2026).

## Traps for a third implementation

General knowledge of Java and Python, not found in this repository's code; check it against yours.

- The page's 28 white-space characters are exactly the 25 of Java's `Character.isWhitespace` plus the three non-breaking spaces U+00A0, U+2007 and U+202F. The fixtures catch dropping U+00A0 and U+202F but **not** U+2007, so a plain `isWhitespace` passes all of them.
- Java's `String.trim()` strips everything up to U+0020 (TRIM_JAVA is not exercised).
- Java's `String.split(",")` drops trailing empty strings; the trailing-comma case is exercised and caught.
- `Pattern.CASE_INSENSITIVE | UNICODE_CASE`, `equalsIgnoreCase` and `toLowerCase` treat U+017F (long s) as `s`; CF2, CF3 and CF7 are not exercised.
- Python's `\d` and Java's `Character.isDigit` accept non-ASCII digits; the page says ASCII digits only, and the fixtures catch this one.
- A time difference that is exactly 3.0 s or 300.0 s on paper is not always exactly that in floating point when the times are not whole seconds (in a random test of microsecond times, an exact 3.0 s difference computed above 3.0 in 0.1 to 0.6 % of cases and an exact 300 s one in 1.5 to 14 %); the fixtures use whole seconds, where it is exact.
