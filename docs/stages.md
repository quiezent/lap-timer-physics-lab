# Stages: mark a road once, drive it as often as you like, compare the drives

Release 1.4 of the app adds **Stages**. A stage is a stretch of road with a start and a finish that you mark yourself, on the phone, by driving it once. After that you arm the stage, put the phone in its mount, and the app starts recording as you
come to the start line and stops after the finish line, every time. Each drive is kept as a **run** of that stage, and later you can put two runs side by side and see where one was ahead of the other.

The driver asked for this for the roads they drive (the Dash highways, KKB to Fraser, Fraser to Bentong) that are also used as rally stages. This page is the plain-words guide. The step-by-step method with every constant is
[`stage-algorithm.md`](stage-algorithm.md); the files are [`stage-format.md`](stage-format.md); how well it was checked is in [`validation.md`](validation.md).

## Read this first: the rule for open roads

They told me these are **open roads with normal traffic**. So I built the stages in a deliberately limited way, and I would like contributors to keep it that way:

- **While the car is moving the screen shows no time, no difference and no comparison.** It shows words: ARMED, RECORDING, ON STAGE, FINISHED, SAVING, and a reminder to drive legally and safely. The numbers come back when the car has stopped
  (speed under 2 km/h for 5 seconds of fresh GPS fixes; if the GPS has gone quiet or the speed is unknown, the answer is "not stopped"). The main screen does the same: no speed, no clock, no lap time, no G while a stage is armed or recording.
- **The only thing the phone does on the move is one beep at the start line, two at the finish line and a low tone if a run is given up.** No spoken delta, no "you're 2 seconds down", no prompt to go faster. The beeps can be switched off.
- **Nothing needs touching on the road.** Marking, arming and disarming are done parked. The first time you press ARM or MARK, the app shows a short text about timing yourself on a road (obey the rules; a time is never a reason to break them; where timing is allowed the times
  mean what they say, elsewhere use the comparison to understand how you drove) and waits for you to accept it.
- **The times are not official and not precise enough to settle a bet.** They are good to a few tenths of a second (below).

This is a limit on what I will build for open roads, not a verdict on the driver. On a closed road, a permitted event or a track the same code gives the same numbers, and a live display could be added for those places on purpose, with its own switch.
It is not in 1.4 (the circuit coaching described at the end is on the list for the track, not for the road).

## What you do

**Mark a stage (once per road, once per direction).**
1. Main screen, **Stages**, **Mark a new stage**. The app starts recording and says *Step 1 of 2*. Type a name for the stage (you can change it later).
2. Park at the start, off the road, and keep the car still. The **Mark start** button turns on when the last few GPS fixes agree that the car is still and the position has settled (4 fixes in a row at under 0.8 m/s, each good to 12 m or better, within 6 m of each other). Press it.
3. Drive the stage as you normally would, with the phone in its mount. Nothing is shown. Park at the finish, off the road, still, and press **Mark finish**.
4. The app builds the stage from the drive (below) and opens it. The marking drive is scored against the new stage and saved as its first run (it starts from rest and ends braking to a stop, so it is the least precise kind of run: see *How accurate*). If the drive cannot make a stage it says why in words (the car never moved, too few GPS fixes, under 300 m, or the finish is less than 150 m from the start, which is a loop) and nothing is saved.

A road driven in the other direction is a different stage: the timing lines count only a car travelling the way you marked them, so mark KKB to Fraser and Fraser to KKB separately.

**Drive it.** Open the stage, **Arm this stage**. From then on, with only the GPS on and nothing recorded, the app waits. When the car is moving within 400 m of the start line, it starts recording (so a stop at a junction near the start, or a queue, does not start it;
moving off does). The run is timed from the instant the car crosses the start line to the instant it crosses the finish line, and the recording runs for 5 more seconds and stops. The run is saved, and the phone arms itself again for the next one. Each run is a full session recording like any other (about 0.6 MB a minute, so a 25-minute stage is about 16 MB), kept in the app's folder and copied to `Downloads/TrackLogger`. It will not trigger again until the car
has been more than 600 m from the start line. If you never reach the start line (you turned off, or 5 minutes went by) the recording is thrown away and the low tone sounds. An armed phone that nobody uses switches itself off after 3 hours.

**Look at it, parked.** The Stages screen shows the last run once the car has stopped: its time and how it compares with your best. Open the stage for the list of runs, newest first. Tap a run to compare it with your **best** other run, the **previous** one, or the **first**:
the total difference, a graph of how far ahead or behind you were at every point of the road, and the difference by section (the shortest of 250 m, 500 m, 1, 2, 5 or 10 km that gives at most 12 sections). The slowest and fastest sections are marked.
A difference smaller than what the GPS can tell apart is called **about the same** (never less than 0.4 s; about 0.5 s for two rolling runs, about 1 s if one end is a standing start); the page says how big that band is for the two runs it is showing.

**Recordings you already have.** *Score the recordings on this phone* looks through the recordings stored in the app and finds every pass of the stage in them, including drives made before the stage existed or with the phone not armed. *Score a recording file* does the same for one file you pick (for example one in `Downloads/TrackLogger` or one you copied
back from a computer). Scoring a recording twice changes nothing: runs are named after the recording they came from.

**Keep your stages safe.** The app turns Android's cloud backup off, so **uninstalling it deletes the stages** (the recordings in Downloads stay). *Back up stages* writes one zip into `Downloads/TrackLogger/`; *Restore from a file* reads one back, on this phone or another, and merges it
(a stage already there keeps its own name and gets the runs it was missing); *Share the last backup* sends the file anywhere. Do this after you mark a stage. A backup holds the routes you drove, which show where you live if a road starts at your door: treat the file like the recordings.

## How a stage is made

The car was parked on the start mark, so a line exactly there could be "crossed" by GPS jitter before the car moves, or missed. The start line is therefore **20 m along the recorded route from the start mark**, and the finish line 20 m before the finish mark; a car pulling away from rest crosses the first at about 40 km/h, where a metre of GPS error is a tenth of a second and not a third.
The recorded route zigzags with the GPS noise and is a little longer than the road, so in simulation the lines came out **15 to 18 m of road** from the marks, not 20. If you want a line at a particular place (a junction, a sign), park 15 to 20 m beyond it when you mark.
Each line is 50 m wide (25 m each side of the road's centre line as driven) and counts only a car going the way you drove. Every run is timed between those same two lines, so the 20 m is the same for all of them and cancels in a comparison. The route between the lines is your marking drive, thinned to a vertex every 3 m or more, and it gives every run the same ruler:
the app records the time at which the car had reached each 25 m of the route.

## How accurate

GPS gives one fix per second and a car covers 15 to 30 m in that time, so the app finds the instant of crossing by fitting a smooth curve through the two fixes either side of the line, using the speed and direction the phone reported (the lap timer does the same thing, and the code agrees with it to the last digit on the real Sepang log).
One metre of error in where the line is, or in where GPS puts the car, is `1 / speed` seconds: 0.033 s at 108 km/h, 0.1 s at 36 km/h. What I measured on a simulated road with a known true time (1 σ of the error in the stage time):

| Case | Error |
|---|---|
| Rolling start and finish at about 108 km/h (30 m/s), 2.5 m GPS noise | 0.11 to 0.12 s |
| The same at about 72 km/h (20 m/s) | 0.17 to 0.18 s |
| Standing start and a braking stop at the finish mark, 3 m noise (lines crossed at about 35 and 45 km/h) | 0.34 to 0.39 s |
| The same with the car parked 10 m short of the start line | 0.39 to 0.48 s |
| A 12 s GPS dropout in the middle of the stage | stage time unchanged (the lines are crossed on good fixes); the graph inside the gap is off by up to about 0.5 s |

The error of one stage time is about `g x sqrt(1/v_start² + 1/v_finish²)`, where `g` is the GPS error in metres and the speeds at the two lines are in m/s. With `g` set to the noise I simulated (2.5 to 3 m) this matches what I measured to within about 10 %; the app assumes `g` = 3 m for its "about the same" band, which is a little pessimistic where the noise was 2.5 m (the formula says 0.14 s for the rolling case where I measured 0.12 s). **The slower the car is going at a line, the worse the time**. Mountain roads are slow. At 50 km/h at both lines one stage time is good to about 0.3 s, two runs of exactly the same true time differ by 0.4 s (1 σ), and the "about the same" band the app draws is about 0.9 s.

Two drives of exactly the same true time therefore differ by about 0.2 s (1 σ) with fast rolling ends and about 0.5 s with a standing start, which is why a difference under about twice that is called "about the same". Tree cover, cuttings and tall buildings make the GPS worse and the page shows a note
when a run had a gap of more than 5 s, wandered more than 100 m from the route, crossed a line between fixes more than 2.5 s apart, or had a worst reported accuracy over 25 m. I have **not** timed a stage on a real road against a stopwatch or a second device; the simulations share the assumptions of the method (two sets of them: mine in Java, and an independent one in Python written from the method page; they agree where their roads agree: [`stage-algorithm.md`](stage-algorithm.md) section 9).

## When something looks odd

| What you see | Likely reason |
|---|---|
| *Mark start* will not turn on | The car is still moving, or the GPS has not settled (the status box at the top says which). Wait a few seconds with the car stopped, under open sky if you can. |
| The stage was refused after the marking drive | The sentence says why. *The car did not move*, *too few GPS fixes* (a long tunnel or a phone that lost its fix), *under 300 m*, or *a loop* (finish within 150 m of the start). |
| Nothing recorded at the start | The phone was not armed, was more than 400 m away while stopped, location was switched off, or Android stopped the app (set *Battery: allow background* to Unrestricted). A run is only started by a car that is moving. |
| A run says *did not finish* | The recording ended or was stopped by hand before the finish line, or the car stood still for 20 minutes, or the run took far longer than the road is long. The recording is kept and can be scored again. **If you drove all of it and stopped at the finish**, the car may have crept over the line slower than 5 km/h, which is not timed: the line is 15 to 18 m before where you parked (a few metres in a rare case). Keep rolling through the finish and stop beyond it. |
| The marking drive (the stage's first run) says *did not finish* | The same reason: that drive ends braking to a stop at the finish mark. The stage is fine; drive it once more as a normal run. In my simulations this happened to 3, 0 and 25 of about 500 such drives on three seeds, the 25 on one stage. |
| Two runs "do not line up" | They were not timed against the same stage file (a stage restored from a backup under a changed route, for example). |
| A run is many seconds slower than the others and the graph shows where | Traffic, a stop, a wrong turn or a GPS gap; the notes under the comparison say if the GPS was poor there. |
| A run is *tens of seconds* off and the graph jumps at the very start or the very end | **Tight stacked hairpins at a line.** A line is 50 m wide; if the road doubles back twice within 25 m of the start or finish line, the car can cross it on the wrong leg. In simulation, legs 30 and 20 m apart were always right, 16 m was wrong in 0 to 6 runs of 100 and 12 m in 29 to 63 of 100. Mark the start and finish away from tight bends. The app does not check for this yet. |

## What I did not check

I have not run the stage screens on a phone: the checks below ran on a PC against fakes of the few Android classes they use. I have not driven a stage on a road. That leaves the look of the screens and the size of the buttons, how Android treats the service when the screen is off for an hour (battery managers differ), whether the beeps are audible over a
car, the GPS under tree cover, the real time to the first fix after ARM, and what happens when Android kills the app in the middle of a run (the run is then not saved, but the recording up to that point is). There is no ground truth from a second timer, and the first real stage will be the first test of the whole chain.
The list of checks and what each does is in [`validation.md`](validation.md); the numbers there are what the tests printed.

## What is in 1.4 and what is next

In 1.4: marking, arming, automatic start and stop, runs, comparison, scoring old recordings, backup and restore, beeps, the open-road rules above. Not in 1.4 and next in line, in the order the driver chose:

1. **Sepang from earlier sessions.** Load a previous Sepang session (or several) on the phone as the baseline, so that predictions and comparisons work from the first lap of a new session and not after a few laps of collecting data.
2. **A Coaching button for the circuit.** Predicted lap time and a live delta against your own best-of-sessions lap, from lap 1, for the track only, with the same glanceable-not-distracting rule, and a debrief after the session. Not for stages on open roads.

## Where the code is

| Class | What it does |
|---|---|
| `StagesActivity` | The screen: list of stages, a stage with its runs, the comparison with its graph, the marking pages, backup and restore |
| `StageText` | Every word and number the screens show, as plain functions (so they are tested) |
| `StageLive`, `ResultGuard` | What the service tells the screen; the "has the car stopped" rule that keeps numbers off the screen |
| `StageMarker`, `MarkSession`, `StageBuilder` | Marking: the still-car test, the two marks, and building the stage from the drive |
| `Gate`, `Geo`, `Pt`, `Route` | The timing line, the flat map frame, a placed GPS fix, and the route with its distances |
| `StageRun`, `Scorer`, `StageSession`, `StageWatcher`, `ArmedWatcher` | Following a run fix by fix, scoring a whole recording, the live run, and the armed waiting |
| `Compare`, `RunResult` | The comparison of two runs, and the summary kept for each run |
| `Stage`, `StageFiles`, `StageStore`, `StageBundle`, `StageExporter`, `StageRescore`, `LogReader` | The files: reading and writing stages and runs, the backup zip, scoring old recordings |
| `Beeper` | The beeps |
| `RecorderService`, `Recorder`, `MainActivity` | The service gained the armed and marking modes; the recorder passes each GPS fix to the stage code and writes the stage events; the main screen shows words instead of numbers while a stage is armed |

On a computer: `python analysis/stage_compare.py` scores recordings against a stage file, builds a stage from a marking recording and compares runs (it was written from [`stage-algorithm.md`](stage-algorithm.md) alone, without reading the Java), and
`python sim/simulate_stage.py` simulates driven stages with a known true time.
