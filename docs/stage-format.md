# Stage files (format version 1)

The files behind the **Stages** feature of release 1.4 and 1.5: what a saved stage looks like, what a saved run looks like, what a planned stage (1.5) looks like before it has a road, what the recorder adds to the session CSV,
and the bundle that carries stages from the phone to a computer, or from one phone to another. How the numbers in them are made is in [`stage-algorithm.md`](stage-algorithm.md);
what the feature is for and how to use it is in [`stages.md`](stages.md). The session CSV itself is [`log-format.md`](log-format.md).

The files are **plain text on purpose**: you can read them, the Python tools in [`../analysis`](../analysis) read the same files, and an iPhone app can write them.
The Android reader is `StageFiles.java`; if this page and the reader disagree, the reader wins and this page has a bug.

## Where they live

- On the phone, in the app's private storage: `stages/<stage id>/stage.txt` and `stages/<stage id>/run_<run id>.txt`, and, for a planned stage that has not been driven yet, `stages/<stage id>/plan.txt` instead of `stage.txt`. Nothing else can read them, and
  **they are deleted when the app is uninstalled** (the app turns Android's cloud backup off, because a recording is not something I want leaving the phone unasked).
  The **Back up stages** button on the Stages screen writes a bundle (below) to `Downloads/TrackLogger/`; do that after you mark a stage.
- On a computer: anywhere. `analysis/stage_compare.py` takes a stage file, a folder of them, or a bundle.

An **id** is 1 to 64 characters from `a-z A-Z 0-9 _ -` and nothing else (it is used in file names). The app makes stage ids like `st20261013_110005` (the local time the
stage was marked, with `b`, `c`, ... `z` on the end if that id is taken) and run ids like `20261013_121500_1` (the session file's name without `session_` and `.csv`, then the
number of the run in that file; a file name with characters that cannot go into an id gets a safe form with a short hash, the exact rule is in [`stage-algorithm.md`](stage-algorithm.md) section 6 and the 35 examples in
[`../analysis/stage_fixtures/run_ids.csv`](../analysis/stage_fixtures/run_ids.csv)). Because a run is named after the recording it came from, scoring the same recording again changes nothing.

Every file starts with a line that names the kind and the version and **ends with a line `end`**. A file without the `end` line (a write that was cut short) is rejected as a whole,
never half-read. The app writes through a temporary file that is renamed over the old one, so a cut-short write leaves the old file whole.
Lines are `key value...` with single spaces; numbers use `.` as the decimal point; a key the reader does not know is skipped (so a later version can add lines).

## `stage.txt`

```
TRACKLOGGER-STAGE 1
id st20261013_110005
name Dash Highway north
kind road
created_utc_ms 1791871205000
origin 3.1234567 101.7654321
start_mark 3.1234567 101.7654321
finish_mark 3.1801234 101.7712345
start_gate 14.945 10.867 32.180 25.0
finish_gate 2575.729 -448.611 66.365 25.0
route 213
r 14.945 10.867
r 19.641 16.685
...
r 2575.729 -448.611
end
```

(The numbers are those of the road-1 test stage in [`../analysis/stage_fixtures/road1/stage.txt`](../analysis/stage_fixtures/road1/stage.txt), with the id and name changed. The first `r` is the middle of the start line and the last is the middle of the finish line, which are 20 m in from the marks because the car was parked on the marks; the origin is the start mark. A stage made from pins (1.5) has its lines **exactly at the pins**, on the road the car drove (for a place typed or pasted, the point of the drive nearest to it), so there the first and last `r` are those points and not 20 m in.)

| Key | Values | Meaning |
|---|---|---|
| `id` | id | Must equal the name of the folder it is in. |
| `name` | text to the end of the line | At most 60 characters, no commas or semicolons, no control characters; the app renames a stage by rewriting this line only. |
| `kind` | `road` | The only kind there is. A reader that meets another kind refuses the file. |
| `created_utc_ms` | integer | Wall clock when the stage was marked (0 if unknown). |
| `origin` | `lat lon` (degrees, 7 decimals) | The origin of the flat frame (below). It is the start mark. |
| `start_mark`, `finish_mark` | `lat lon` | Where the driver parked and pressed MARK START and MARK FINISH: the average of up to five still fixes. For a stage made from pins (1.5): the place the driver gave for the pin (typed or pasted coordinates), or, for a pin put on the drawing of the drive, the point of the drive it was put on. For reference; the timing uses the gates. |
| `start_gate`, `finish_gate` | `cx cy bearing half_width` | A timing line: centre (metres east, north of the origin), the direction of travel it counts (compass degrees clockwise from north), half its width (metres). |
| `route` | integer n | How many `r` lines follow; the file is rejected if the number is wrong. At least 2, at most 200 000. |
| `r` | `x y` | A vertex of the route in the flat frame, metres, from the middle of the start line to the middle of the finish line. 3 decimals. |

**The flat frame.** A point `(lat, lon)` is placed at `x = radians(lon - lon0) * R * cos(radians(lat0))`, `y = radians(lat - lat0) * R` with `R = 6378137` and `(lat0, lon0)` the `origin`.
`x` is metres east, `y` metres north. It is the same formula as the lap timer's, and over a long stage it is off by well under a tenth of a percent; since every run of a stage is placed the same way, the comparison does not feel it.

A stage is made once and **does not change** afterwards, except for its name. That is what keeps the runs saved against it comparable.

## `plan.txt` (release 1.5)

A **planned stage** is a stage that has a name and two places, and no road yet. The driver typed or pasted the two places (from a map). In release 1.5 the plan waits for a drive: the driver drives the road with an ordinary recording, opens the plan and chooses **Make the stage from a recording**, and the phone puts the two pins at the two places on that drive ([`stage-algorithm.md`](stage-algorithm.md) sections 11.1 to 11.3). When the stage has been made the plan file is replaced by a `stage.txt` **in the same folder, under the same id**, so everything that pointed at the plan now points at the stage. (Release 1.6 will let the driver *arm* a plan, so that the first drive records by itself: section 11.9, not built yet.)

```
TRACKLOGGER-PLAN 1
id st20261014_011700
name Fraser to Bentong
created_utc_ms 1791871205000
start_pin 3.5683210 101.7461230
finish_pin 3.5214530 101.9087640
end
```

| Key | Values | Meaning |
|---|---|---|
| `id` | id | Must equal the name of the folder it is in. |
| `name` | text to the end of the line | As for a stage. |
| `created_utc_ms` | integer | Wall clock when the plan was made (0 if unknown). |
| `start_pin`, `finish_pin` | `lat lon` (degrees, 7 decimals) | The two places as the driver gave them. They need not be on the road: the lines are put on the road the car drove, at the nearest point (section 11.2). |

A folder that holds a `stage.txt` is a stage, whatever else is in it; a `plan.txt` beside a `stage.txt` is ignored (and removed the next time the stage is saved). A folder with only a valid `plan.txt` is a planned stage; a folder with neither is not listed. A plan has no runs.
**Plans are not in the backup bundle** (below): they are two places and a name, quick to type again, and a bundle holds what was learned from driving.

## `run_<run id>.txt`

```
TRACKLOGGER-RUN 1
stage st20261013_110005
run 20261013_121500_1
csv session_20261013_121500.csv
utc_ms 1791875123456
finished 1
status ok
total_s 1859.742
length_m 6254.20
step_m 25.0
quality 1672 1.000 3150.0 6.0 11.7 1.000 1.000
line_speeds 14.82 21.30
n 252
p 0.000 1.612 3.201 ... (20 values to a line)
p ...
end
```

| Key | Values | Meaning |
|---|---|---|
| `stage`, `run` | ids | The stage this is a run of, and this run's own id (the file name is `run_<run id>.txt`). |
| `csv` | file name | The session file the run was recorded in, `""` if unknown. The CSV is the source of truth: a run file can always be rebuilt from it (**Score old recordings** in the app, or `stage_compare.py score`). |
| `utc_ms` | integer | Wall clock at the start line, 0 if unknown. |
| `finished` | `1` or `0` | 0 means the run did not finish: it has a reason in `status`, and no `total_s` and no profile. |
| `status` | text | `ok`, or why it did not finish, in words (no commas). |
| `total_s` | seconds, 3 decimals | The time from the start line to the finish line. Only when finished. |
| `length_m` | metres | The stage's route length when the run was scored. |
| `step_m` | 25.0 | The spacing of the profile. |
| `quality` | 7 numbers | `fixes max_gap_s max_gap_at_m max_acc_m off_route_max_m start_gap_s finish_gap_s`: how many GPS fixes were placed on the route between the lines; the longest time between two of them and the route distance where it began; the worst reported GPS accuracy (-1 if none was reported); the furthest the car was from the route; the time between the two fixes either side of each line. |
| `line_speeds` | `start finish` (m/s) | The speed at each line, -1 if unknown. A crossing at low speed is timed less well (see [`stage-algorithm.md`](stage-algorithm.md)). Files from before these were kept have no such line. |
| `n` | integer | Number of profile values: `ceil(length_m / step_m) + 1` (a 6254.20 m route gives 252, one every 25 m and the last at the end of the route). Only when finished. |
| `p` | up to 20 numbers | The **profile**: the time, in seconds after the start line, at which the car had reached route distance `k * step_m`, for `k = 0 ... n-1` (the last entry is at the end of the route, `length_m`, and so is not at `(n-1) * step_m` unless the length is a multiple of 25). 3 decimals. |

Two runs are **comparable** when they are finished, belong to the same stage, have the same `step_m` and the same `n`, and their `length_m` agree to half a metre.

## What the recorder adds to the session CSV

New `E` events; the reader of [`log-format.md`](log-format.md) ignores names it does not know, so every old tool still reads the file. The header line becomes `# TrackLogger 1.4 ...` (`1.5` from release 1.5).
The time of an event is in microseconds on the recording clock like every other row; for a line crossing it is the **interpolated moment the car crossed the line**, not the time the app noticed.

| Event | Values | Meaning |
|---|---|---|
| `STAGE` | `id` | This recording is a run of the stage with this id (written with the first GPS fix after the recorder was started by an armed stage). |
| `STAGE_START` | none | The car crossed the start line. |
| `STAGE_FINISH` | `seconds` | The car crossed the finish line; the stage took this long (3 decimals). |
| `STAGE_DNF` | `reason` | The run started and did not finish (the run took far too long, or the car stood still for 20 minutes, or the recording was stopped before the finish line). |
| `STAGE_ABORT` | `reason` | The start line was never crossed (the car moved away from it, or 5 minutes went by). The app deletes such a recording after writing this row. |
| `MARK_START`, `MARK_FINISH` | `lat, lon, accuracy_m` | While marking a new stage: where the driver parked and pressed the button (7 decimals), and the mean reported accuracy of the fixes averaged. |
| `PLAN` | `id` | (release 1.6, **not written by 1.5**) This recording is the first drive of the planned stage with this id. |
| `PLAN_START`, `PLAN_FINISH` | none | (release 1.6, not written by 1.5) The car has passed the start pin / the finish pin (section 11.9: the nearest point and 30 m beyond; the time is that of the fix that showed it, not of a crossing). |
| `PLAN_DNF` | `reason` | (release 1.6, not written by 1.5) The planned drive was given up after the start pin (the car stood still too long, or the drive took far too long). The recording is kept. |
| `PLAN_ABORT` | `reason` | (release 1.6, not written by 1.5) The start pin was never reached. The app deletes such a recording after writing this row. |

The four `PLAN*` rows are written down here so that the file format, the Python tools and an iPhone app can be planned against them; release 1.5 reads and writes `plan.txt` but does not yet record a first drive by itself, so none of its CSVs has them.

**A stage can be rebuilt from a marking recording alone:** the position in `MARK_START` is the origin and the start mark, `MARK_FINISH` is the finish mark, and the GPS fixes (`L` rows) between the two events are the drive.
`stage_compare.py build` does exactly that, and the Android tests check that the stage built live and the stage rebuilt from the file agree to 5 cm.

## The bundle

**Back up stages** writes `Downloads/TrackLogger/TrackLogger-stages-YYYYMMDD-HHMMSS.zip`; **Restore from a file** reads one (and *Share the last backup* sends the newest one to Drive, email, a messenger). It is an ordinary zip file (deflate) with this layout:

```
tracklogger-stages/<stage id>/stage.txt
tracklogger-stages/<stage id>/run_<run id>.txt
```

The same layout as the app's own folder, under a fixed top folder (the app and `stage_compare.py` also read a zip without the top folder, such as one made by hand from the `stages` folder; folder entries are ignored). It holds the stages and the run summaries, **not the session CSVs** (those are already in `Downloads/TrackLogger/`); with the stage file and the CSVs, every run can be rebuilt.
The bytes are the same for the same stages every time (entry times are fixed), so two backups of an unchanged set are identical.

**Restoring is careful, because a bundle comes from outside.** The reader ignores every entry whose name is not exactly one of the two patterns above (so no path with `..`, no absolute path, no backslash, no other file name ever
reaches the disk), refuses a zip larger than 50 MB, more than 5000 entries, an entry that expands to more than 5 MB, or entries that together expand to more than 32 MB (a zip bomb: the sizes are counted while reading, not taken from the zip), and parses every file with the same strict reader as the app uses for its own files
(a bad one is skipped and counted, never half-restored). Then, for each stage folder in the bundle, in this order:

- a stage with that id is on the phone and is **the same road** (the same origin, lines and route, to a millimetre): it is the same stage. The phone keeps its own name and gets the runs it did not have (a run id it already has is left alone);
- otherwise, **the same road is already on the phone under another id** (a copy that came in earlier): the runs are merged into that stage, never a second copy of the road;
- otherwise, no stage with that id is on the phone: it is added, with its runs;
- otherwise (the id is taken by a **different road**): it is added as a new stage under a new id, named `... (imported)`, with its runs pointed at it.

Runs in a folder whose stage file is missing or bad go to the stage of that id on the phone if there is one, and are counted as skipped if not. A run is only taken if its `length_m` is within half a metre of its stage's.

Restoring the same bundle twice changes nothing the second time (the result says "already there").

The result is a sentence: how many stages were added or merged, how many runs were added, how many were already there, how many files were skipped.
