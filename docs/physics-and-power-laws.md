# The physics of a lap, read as power laws

This page is the physics behind the report: three laws, why each is a straight line in the right coordinates, what the Sepang laps say about them, and where the laws stop applying.
I'm Claude; the derivations are textbook, the numbers are from the real session in this repository.

## What I mean by "power law"

A power law is a relationship `y = c · x^p`. The exponent `p` is also an **elasticity**: a 1% change in `x` changes `y` by about `p` per cent. On log-log axes it is a straight line of slope `p`.
(Statisticians also use the words for heavy-tailed distributions. I use them only in the first sense; with 180 corner-laps I cannot say whether the time lost has a power-law tail, and I don't claim it.)

The starting thought from the driver was "exponential physics into linear execution". The physics of a car is made of power laws, not exponentials, and that is the useful part: each law below becomes
a straight line in a simple coordinate, and a straight line is what a driver can follow and a program can fit.

## The exponents

| Quantity | Law | Exponent | If speed rises 10% |
|---|---|---|---|
| Stopping distance | `d = v² / 2a` | 2 in speed | +21% |
| Kinetic energy to shed | `½ m v²` | 2 in speed | +21% |
| Radius needed at the grip limit | `r = v² / a` | 2 in speed | +21% |
| Corner speed at the grip limit | `v = √(a r)` | 1/2 in radius and in grip | (+10% grip gives +4.9% speed) |
| Drag force, downforce | `½ ρ C A v²` | 2 in speed | +21% |
| Drag power | `F v` | 3 in speed | +33% |
| Top speed against power | `pw/v = k v²` gives `v = (pw/k)^(1/3)` | 1/3 in power, -1/3 in drag | (+10% power gives +3.2% top speed) |
| Power-limited acceleration | `a = P/(m v)` | -1 in speed | -9% |
| Time over a fixed distance | `t = s / v` | -1 in speed | -9% |
| Apex-speed change per metre of braking point | `a / v` | -1 in speed | |

## Law 1: braking is a straight line in speed squared

At a steady deceleration `a`, `v² = v0² − 2 a d`. In coordinates (distance, speed²) a braking zone is a **straight line of slope -2a**: its slope is the deceleration, and where it meets the corner's speed² is the braking point.
Example from the log: T1, from 236 km/h to the slowest point at 64 km/h at the median 0.93 g, takes `(65.6² − 17.8²) / (2 × 0.93 × 9.81) = 219 m`; the median braking onset is 231.5 m before the reference apex (the zone ends at the lap's slowest point, a little before it).

**The last metres decide the apex speed.** Move the braking point later by `δ` at the same deceleration and you arrive with `v² ` larger by `2 a δ`, so the apex speed changes by `a δ / v` (to first order). That is largest where `v` is smallest:
at T9, `1.02 g / 50 km/h` is **2.6 km/h per metre**; at T11 it is 0.8. A 5 m error at T9 is 13 km/h at the apex. It is an upper bound, because a trail-braking car decelerates less near the end of a zone and drivers compensate.

**What the laps say.** 98 braking zones: the straight line in speed² fits with a median R² of 0.973 (99% of zones above 0.9). The five heavy zones (T1, T4, T7, T9, T15) brake at a steady 0.82 to 1.02 g. At T1 the 300 m up to the apex takes
0.027 s longer for each metre earlier the braking starts (90% interval 0.017 to 0.040, 13 loops), which prices 8 m at about 0.2 s.
**What they don't say:** firmer braking on its own did not make T1 to T3 faster (r = -0.18); it went with a *lower* apex speed.

## Law 2: corner speed is set by radius to the power one half

At the grip limit the sideways acceleration is `v² / r = a`, so `v = √(a r)`. In coordinates (√radius, speed) every corner driven at the same grip falls on one straight line through the origin whose slope is `√a`.
Example: T7's driven radius is 77 m and the other ten corners' median grip at their slowest points is 0.95 g, so the law gives `√(0.95 × 9.81 × 77) = 26.8 m/s = 96 km/h`; T7's measured slowest point is 96.1 km/h.

**Sensitivities a driver can feel.** 4% more speed needs 8% more radius (1.04² = 1.082): at T7 about 6 m of line. A corner taken with a smaller radius than the car needs costs speed at the same rate.
And one tenth of a g of cornering grip, on a car that corners at 1.2 g, is worth 3 to 4 seconds on a Sepang lap in my model, because speed goes with the square root of grip.

**What the laps say.** I took the radius the car actually drove at each corner's slowest point (1 / curvature, curvature from the gyro's yaw rate divided by speed) and fitted speed against radius on log axes over the eleven corners that
have a speed minimum of their own: exponent **0.50** (90% interval 0.48 to 0.53; dropping any one corner gives 0.49 to 0.52; 0.52 without the two near-flat-out kinks T12 and T13).
The sideways g at the slowest point stays between 0.86 g (T9) and 1.05 g (T11) at every corner, which is what the law predicts when the same tyres run at about the same limit. T9 and T15 use the least (0.86 and 0.87 g).
Across loops within one corner the same relationship shows up (T9: exponent 0.59 over 14 loops, 0.50 without the one loop that arrived too fast), though with `r = v²/a` the data cannot say whether the driver chose the arc or the speed did.

## Law 3: straights, and what an exit speed is worth

Engine power at the wheels `P` pushes with `P/(m v)`; drag takes `½ ρ CdA v² / m`. So `a(v) = pw / v − k v²`, with `pw = P/m` in W/kg and `k = ½ ρ CdA / m`.
Fitted to the clean laps' straights: `pw = 138.6 m²/s³`, `k = 0.000187 /m`, a curve that gives 0.49 g at 100 km/h and 0.195 g at 200 km/h and a top speed of 326 km/h (the car's published top speed is 318 km/h).
The straights' push is about three quarters of what the brochure figures say; I cannot yet say why (heavier car, hot air, part throttle, my sensor), and the log never goes above 236 km/h, so push and drag can't be separated.

**An advantage in speed at the start of a straight decays but never reaches zero.** If two cars leave a corner `Δv0` apart, then `dΔv/ds = (A'(v)/v) Δv` with `A'(v) < 0`, so the gap shrinks along the straight but stays positive. Integrating that along the straight gives the
time saved per km/h at the exit, `σ = ∫ v0 / v³ · exp(∫ A'(v)/v ds) ds`. For the three straights that follow a single corner at Sepang the physics gives **0.023 to 0.032 s per km/h**, and the loops give 0.011 to 0.018 (a third to a half of that,
with wide intervals; I don't know why). As a rule of thumb I'd use **0.01 to 0.03 s per km/h**, and the exits onto the main straight are the ones that matter.

**What a km/h is worth at the slowest point** (my model, one corner driven at 97% of its limit speed, the rest perfect): 0.02 to 0.05 s per km/h, largest at slow corners that open onto a long stretch (T15 0.046, T14 0.044, T4 0.043, T1 0.040) and smallest at T2 (0.017), whose stretch is short.
Lap time is the sum of `s/v` over the lap, so a km/h is worth roughly `s/v²` where it is lost: most where the car is slow and the stretch is long.

## Do the laws reproduce the lap?

Only if I tell the model the grip. My point-mass lap solver (`physics/gt3_model.py`, `sim/simulate_sepang.py`, `sim/calibrated_lap.py`) with the published figures gives a perfect lap of 2:25.2, eight seconds faster than the real best (2:33.07). With the engine and drag the phone measured, 2:29.7;
adding braking at the measured 0.92 to 1.01 g, 2:32.6 to 2:34.0; and with cornering grip between 1.1 and 1.3 g the perfect lap is 2:29.7 to 2:37.3. The real laps (2:31.78 ultimate, 2:33.07 best, 2:34.51 typical) sit inside that range, but the model can't say how far inside:
it is a consistency check on the shape of the physics, not a prediction. That is why the engine compares laps with laps and uses the model only to order corners. (Report, section 8.)

## Where the laws stop

- **Grip is not constant.** Tyre temperature and wear, the surface, banking and camber move it. The peak sideways g rises from 1.12 g in the slower half of the corners to 1.21 g in the faster half; published downforce explains about a seventh of that, so I don't claim it as aero.
- **A car is not a point.** Weight transfer, trail braking, the balance between front and rear grip and the driver's line all matter; a steady deceleration is an approximation (R² 0.97, not 1).
- **A phone measures acceleration, not pedals.** "Braking" is deceleration; coasting at 0 g is off both pedals; once the car is power-limited, 70% and 100% throttle look alike.
- **The laws describe the car, not the driver's choices.** They say what a metre or a km/h is worth. Whether to take it, and whether the car stays settled, is judgement that a log cannot supply.
