# -*- coding: utf-8 -*-
"""
powerlaws_figures.py - the figures for reports/<session>/REPORT.md, drawn from powerlaws.analyse().

Called by powerlaws.py; needs matplotlib.  Palette and mark rules follow a validated data-visualisation palette
(blue first, orange second; thin lines; recessive grid; direct labels).  Every figure is a statement in its title.
"""
import math
import os
import textwrap

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt                                    # noqa: E402
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm   # noqa: E402
from matplotlib.ticker import FuncFormatter                       # noqa: E402

G0 = 9.80665
KMH = 3.6

# chart chrome and ink (light surface)
SURF, INK, INK2, MUTED, GRID, AXIS, NEUTRAL = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7", "#f0efec"
# categorical slots, in fixed order
BLUE, ORANGE, AQUA, YELLOW, MAGENTA, GREEN, VIOLET, RED = "#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"
BLUE_DK, BLUE_MID, BLUE_LT = "#184f95", "#6da7ec", "#cde2fb"
CONTEXT = "#c9c8c1"                                                # the other laps: present, but quiet


def style():
    plt.rcParams.update({
        "figure.facecolor": SURF, "axes.facecolor": SURF, "savefig.facecolor": SURF, "savefig.dpi": 150, "figure.dpi": 100,
        "font.family": "DejaVu Sans", "font.size": 9.5, "text.color": INK, "axes.labelcolor": INK2, "axes.edgecolor": AXIS,
        "xtick.color": INK2, "ytick.color": INK2, "axes.spines.top": False, "axes.spines.right": False,
        "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.7, "axes.axisbelow": True,
        "axes.titlesize": 11.5, "axes.titleweight": "bold", "axes.titlelocation": "left", "axes.titlepad": 10,
        "legend.frameon": False, "legend.fontsize": 8.5, "lines.solid_capstyle": "round",
    })


def clock(s):
    m, r = divmod(float(s), 60.0)
    return "%d:%04.1f" % (m, r)


def wrap(text, width=168):
    return "\n".join(textwrap.fill(p, width) for p in text.split("\n"))


def note(fig, text, y=0.006, width=190):
    fig.text(0.012, y, wrap(text, width), fontsize=7.8, color=MUTED, ha="left", va="bottom", linespacing=1.35)


def lede(fig, text, y, width=168, size=9.2):
    fig.text(0.012, y, wrap(text, width), fontsize=size, color=INK2, ha="left", va="top", linespacing=1.45)


def save(fig, figdir, name):
    path = os.path.join(figdir, name)
    fig.savefig(path)
    plt.close(fig)
    return path


def lum(col):
    """Relative luminance of a '#rrggbb' string or an (r, g, b[, a]) tuple in 0-1."""
    if isinstance(col, str):
        h = col.lstrip("#")
        r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    else:
        r, g, b = col[:3]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


# ------------------------------------------------------------------------------------------------ 1. where the time goes
def fig_time_map(S, res, extra, figdir):
    exc = extra["exc"]
    laps = list(range(S.nb))
    arm_b = LinearSegmentedColormap.from_list("b", [NEUTRAL, BLUE_MID, "#1c5cab"])
    arm_r = LinearSegmentedColormap.from_list("r", [NEUTRAL, "#f1a49f", "#c0302f"])
    vmax = 1.2

    def colour(x):
        return arm_r(min(x / vmax, 1.0)) if x >= 0 else arm_b(min(-x / vmax, 1.0))

    fig = plt.figure(figsize=(11.2, 7.6))
    gs = fig.add_gridspec(2, 2, height_ratios=[1.0, 6.2], width_ratios=[40, 1.1], hspace=0.07, wspace=0.075,
                          left=0.115, right=0.925, top=0.855, bottom=0.095)
    ax = fig.add_subplot(gs[1, 0])
    axb = fig.add_subplot(gs[0, 0], sharex=ax)
    ax.grid(False)
    for bb in laps:
        for k in range(S.K):
            x = exc[bb, k]
            if np.isfinite(x):
                fc = colour(x)
                ax.add_patch(plt.Rectangle((k - 0.5, bb - 0.5), 1, 1, facecolor=fc, edgecolor="none", linewidth=0))
                if abs(x) >= 0.4:
                    ax.text(k, bb, "%+.1f" % x, ha="center", va="center", fontsize=8, color="white" if lum(fc) < 0.42 else INK, fontweight="bold")
            else:
                ax.add_patch(plt.Rectangle((k - 0.5, bb - 0.5), 1, 1, facecolor="#f4f3ef", edgecolor="#d6d5cd", hatch="//////", linewidth=0))
            ax.add_patch(plt.Rectangle((k - 0.5, bb - 0.5), 1, 1, fill=False, edgecolor=SURF, linewidth=1.6))
    ax.set_xlim(-0.5, S.K - 0.5)
    ax.set_ylim(S.nb - 0.5, -0.5)
    ax.set_xticks(range(S.K))
    ax.set_xticklabels(S.labels)
    ax.set_yticks(laps)
    ax.set_yticklabels(["loop %d   %s" % (S.nums[bb], clock(S.time[bb])) for bb in laps], fontsize=8.6)
    for t, bb in zip(ax.get_yticklabels(), laps):
        if not S.clean[bb]:
            t.set_color(MUTED)
    ax.tick_params(length=0)
    for sp in ax.spines.values():
        sp.set_visible(False)
    # top strip: mean loss per corner
    pcl = {r["corner"]: r for r in res["per_corner_loss"]}
    xs = [k for k in range(S.K) if S.labels[k] in pcl]
    axb.bar(xs, [pcl[S.labels[k]]["mean_loss_s"] for k in xs], width=0.62, color=BLUE, zorder=3)
    for k in xs:
        axb.text(k, pcl[S.labels[k]]["mean_loss_s"] + 0.008, "%.2f" % pcl[S.labels[k]]["mean_loss_s"], ha="center", va="bottom", fontsize=7.6, color=INK2)
    axb.set_ylim(0, 0.36)
    axb.set_yticks([])
    axb.tick_params(labelbottom=False, length=0)
    for sp in axb.spines.values():
        sp.set_visible(False)
    axb.grid(False)
    axb.text(-0.5, 0.355, "average time lost per lap in each corner's stretch, s", fontsize=8, color=INK2, ha="left", va="top")
    # colour key
    cax = fig.add_subplot(gs[1, 1])
    grad = np.linspace(-vmax, vmax, 256)[::-1][:, None]
    cm = LinearSegmentedColormap.from_list("d", [colour(v) for v in np.linspace(-vmax, vmax, 256)])
    cax.imshow(np.linspace(-vmax, vmax, 256)[:, None], aspect="auto", cmap=cm, extent=[0, 1, vmax, -vmax])   # blue (faster) at the top, red (slower) at the bottom
    cax.set_xticks([])
    cax.yaxis.tick_right()
    cax.set_yticks([-1, -0.5, 0, 0.5, 1])
    cax.set_yticklabels(["1 s faster", "0.5", "0", "0.5", "1 s slower"], fontsize=7.8)
    cax.grid(False)
    for sp in cax.spines.values():
        sp.set_visible(False)
    cax.tick_params(length=0)
    c = res["concentration"]
    fig.suptitle("%d corner-laps account for %.0f%% of all the time lost" % (c["events_ge_half_s"], 100 * c["events_ge_half_share"]), x=0.012, ha="left", y=0.975, fontsize=14, fontweight="bold")
    lede(fig, "Time spent in each corner's stretch against the median lap, one cell per corner per lap: red is slower, blue faster, hatched not rated.\n"
              "The %d cells that lost half a second or more hold %.0f%% of everything lost (everyday scatter is about %.2f s); they look like one-off incidents, not a steady pace gap." % (
                  c["events_ge_half_s"], 100 * c["events_ge_half_share"], c["robust_sd_s"]), 0.925, width=190)
    note(fig, "Cell = time from the middle of the gap before the corner to the middle of the gap after it, minus the median of the other rated laps. A cell is rated only where the GPS is good through "
              "the whole stretch, so loops 1, 3, 11 and 13 (grey names) have fewer rated cells and loop 14, an in/out lap, has none.")
    return save(fig, figdir, "where_the_time_goes.png")


# ------------------------------------------------------------------------------------------------ helpers for traces
def _braking_window(S, k):
    rows = [(-S.ev(b, k, "brake_pos"), S.ev(b, k, "apex_off")) for b in np.where(S.fast)[0] if np.isfinite(S.ev(b, k, "brake_pos"))]
    on = float(np.median([r[0] for r in rows]))
    off = float(np.median([r[1] for r in rows]))
    return on, off


def _sq_axis(ax, lo, hi, ticks):
    ax.set_yscale("function", functions=(lambda v: np.square(v), lambda s: np.sqrt(np.maximum(s, 0.0))))
    ax.set_ylim(lo, hi)
    ax.set_yticks(ticks)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: "%d" % v))


# ------------------------------------------------------------------------------------------------ 2. braking
def fig_braking(S, res, tables, figdir):
    fig = plt.figure(figsize=(11.2, 8.3))
    gs = fig.add_gridspec(2, 3, height_ratios=[1.12, 1.0], hspace=0.52, wspace=0.28, left=0.07, right=0.985, top=0.81, bottom=0.10)
    summ = {r["corner"]: r for r in res["braking"]["per_corner"]}
    for j, label in enumerate(("T1", "T9", "T15")):
        k = S.labels.index(label)
        on, off = _braking_window(S, k)
        i0 = int(S.ia[k] - on - 70)
        i1 = int(S.ia[k] + 45)
        x = np.arange(i0, i1 + 1) - S.ia[k]
        ax = fig.add_subplot(gs[0, j])
        for bb in np.where(S.fast)[0]:
            ax.plot(x, S.V[bb, i0:i1 + 1] * KMH, color=CONTEXT, lw=0.9, zorder=2)
        med = np.median(S.V[S.fast][:, i0:i1 + 1], axis=0) * KMH
        ax.plot(x, med, color=BLUE, lw=2.0, zorder=4, label="median lap")
        a_ = int(round(S.ia[k] - on)); b_ = int(round(S.ia[k] + off))
        xs = np.arange(a_, b_ + 1)
        v2 = np.square(np.median(S.V[S.fast][:, a_:b_ + 1], axis=0))
        A = np.polyfit(xs - S.ia[k], v2, 1)
        ax.plot(xs - S.ia[k], np.sqrt(np.maximum(np.polyval(A, xs - S.ia[k]), 1)) * KMH, color=ORANGE, lw=1.6, ls=(0, (4, 2.5)), zorder=5, label="straight line in speed$^2$")
        _sq_axis(ax, 40, 260, [60, 100, 140, 180, 220])
        ax.set_xlim(x[0], x[-1])
        ax.axvline(0, color=AXIS, lw=0.9, zorder=1)
        sm = summ.get(label, {})
        ax.set_title("%s: %.2f g over %d m" % (label, sm.get("median_decel_g", float("nan")), b_ - a_), fontsize=10.5)
        ax.text(0.97, 0.95, "a straight line in speed$^2$ fits with\nR$^2$ = %.3f (median of %d laps)" % (sm.get("median_r2", float("nan")), sm.get("laps", 0)), transform=ax.transAxes,
                ha="right", va="top", fontsize=8.0, color=INK2)
        ax.set_xlabel("metres from the corner's reference apex")
        if j == 0:
            ax.set_ylabel("speed, km/h (axis spaced as speed$^2$)")
            ax.legend(loc="lower left", handlelength=2.2)
    # (d) T1 onset vs the time it takes
    t1 = res["braking"]["first_corner"]
    rows = tables["first_corner_braking"]
    ax = fig.add_subplot(gs[1, 0])
    xo = np.array([r["onset_m"] for r in rows]); yw = np.array([r["window_s"] for r in rows])
    ax.scatter(xo, yw, s=44, color=BLUE, zorder=4, edgecolor=SURF, linewidth=1.0)
    for r in rows:
        ax.annotate(str(r["lap"]), (r["onset_m"], r["window_s"]), xytext=(4, 4), textcoords="offset points", fontsize=7.6, color=INK2)
    fit = np.polyfit(xo, yw, 1)
    xx = np.array([xo.min() - 2, xo.max() + 2])
    ax.plot(xx, np.polyval(fit, xx), color=ORANGE, lw=1.6, zorder=3)
    ax.set_xlabel("braking starts this many metres before the apex")
    ax.set_ylabel("time for the 300 m up to the T1 apex, s")
    ax.set_title("T1: each metre earlier costs %.3f s" % t1["slope_s_per_m"], fontsize=10.5)
    ax.text(0.03, 0.95, "90%% interval %.3f-%.3f s per metre\nr = %.2f over %d laps (numbers are loops)" % (t1["slope_lo"], t1["slope_hi"], t1["corr"], t1["laps"]), transform=ax.transAxes, va="top", fontsize=8.0, color=INK2)
    # (e) steady deceleration by corner
    ax = fig.add_subplot(gs[1, 1])
    per = res["braking"]["per_corner"]
    labs = [r["corner"] for r in per]
    med = np.array([r["median_decel_g"] for r in per])
    p25 = np.array([r["decel_p25"] for r in per]); p75 = np.array([r["decel_p75"] for r in per])
    order = np.argsort(-med)
    y = np.arange(len(labs))
    ax.hlines(y, p25[order], p75[order], color=BLUE_MID, lw=5, zorder=3)
    ax.scatter(med[order], y, s=44, color=BLUE, zorder=4, edgecolor=SURF, linewidth=1.0)
    for yy, i in zip(y, order):
        ax.text(p75[i] + 0.015, yy, "%.2f g" % med[i], va="center", fontsize=8, color=INK2)
    ax.set_yticks(y)
    ax.set_yticklabels([labs[i] for i in order])
    ax.invert_yaxis()
    ax.set_xlim(0.3, 1.2)
    ax.set_xlabel("steady deceleration, g (median, quartiles)")
    heavy = [m_ for m_ in med if m_ > 0.8]
    ax.set_title("The %d heavy zones sit at %.2f-%.2f g" % (len(heavy), min(heavy), max(heavy)), fontsize=10.5)
    # (f) sensitivity of apex speed to braking point
    ax = fig.add_subplot(gs[1, 2])
    sens = np.array([r["sens_kmh_per_m"] for r in per])
    o2 = np.argsort(-sens)
    ax.barh(y, sens[o2], color=BLUE, height=0.55, zorder=3)
    for yy, i in zip(y, o2):
        ax.text(sens[i] + 0.04, yy, "%.1f" % sens[i], va="center", fontsize=8, color=INK2)
    ax.set_yticks(y)
    ax.set_yticklabels([labs[i] for i in o2])
    ax.invert_yaxis()
    ax.set_xlim(0, 3.0)
    ax.set_xlabel("km/h at the apex per metre of braking point")
    ax.set_title("%d m late: up to %.0f km/h too fast" % (5, 5 * sens.max()), fontsize=10.5)
    fig.suptitle("Braking is a straight line in speed squared, so the last metres of a zone decide the apex speed", x=0.012, ha="left", y=0.975, fontsize=14, fontweight="bold")
    lede(fig, "Stopping distance grows with speed squared, so speed$^2$ falls linearly with distance at a steady deceleration. The grey laps follow the orange line closely until the car is nearly at the corner's slowest point. "
              "Because a/v is largest at low speed, a braking-point error turns into speed at the apex (bottom right): about %.1f km/h per metre at T9." % sens.max(), 0.925)
    note(fig, "Deceleration from the phone's longitudinal accelerometer, speed from GPS fused with the accelerometer. Braking zone = from braking onset to the lap's slowest point. "
              "Apex-speed sensitivity = a / v at the slowest point, an upper bound because a trail-braking car decelerates less near the end of the zone.")
    return save(fig, figdir, "braking_speed_squared.png")


# ------------------------------------------------------------------------------------------------ 3. cornering
def fig_cornering(S, res, tables, figdir):
    rows = tables["corner_speed_by_corner"]
    fit = res["cornering"]["fit"]
    lab = [r["corner"] for r in rows]
    r_ = np.array([r["radius_m"] for r in rows]); v_ = np.array([r["v_min_kmh"] for r in rows])
    lo = np.array([r["v_min_lo_kmh"] for r in rows]); hi = np.array([r["v_min_hi_kmh"] for r in rows])
    fig = plt.figure(figsize=(11.2, 5.9))
    gs = fig.add_gridspec(1, 2, width_ratios=[1.25, 1.0], wspace=0.24, left=0.07, right=0.985, top=0.77, bottom=0.16)
    ax = fig.add_subplot(gs[0, 0])
    xx = np.geomspace(15, 220, 50)
    for g_, ls, nm in ((1.0, "-", "1.0 g"), (0.85, (0, (2, 2)), "0.85 g")):
        ax.plot(xx, np.sqrt(g_ * G0 * xx) * KMH, color=MUTED, lw=1.0, ls=ls, zorder=2)
        ax.text(xx[-1] * 1.02, np.sqrt(g_ * G0 * xx[-1]) * KMH, nm, fontsize=8, color=MUTED, va="center")
    A = np.polyfit(np.log(r_), np.log(v_), 1)
    ax.plot(xx, np.exp(np.polyval(A, np.log(xx))), color=ORANGE, lw=1.8, zorder=3)
    ax.vlines(r_, lo, hi, color=BLUE_MID, lw=3.2, zorder=3)
    ax.scatter(r_, v_, s=46, color=BLUE, zorder=4, edgecolor=SURF, linewidth=1.0)
    offs = {"T2": (-22, -4), "T1": (-21, 5), "T15": (7, -10), "T4": (-22, 7), "T14": (7, 3), "T12": (7, -11), "T5": (7, 4), "T9": (7, -3)}
    for x_, y_, l_ in zip(r_, v_, lab):
        ax.annotate(l_, (x_, y_), xytext=offs.get(l_, (6, 5)), textcoords="offset points", fontsize=8, color=INK)
    ax.set_xscale("log"); ax.set_yscale("log")
    ax.set_xlim(15, 260); ax.set_ylim(38, 175)
    ax.set_xticks([20, 30, 50, 100, 200]); ax.set_xticklabels(["20", "30", "50", "100", "200"])
    ax.set_yticks([40, 50, 70, 100, 150]); ax.set_yticklabels(["40", "50", "70", "100", "150"])
    ax.minorticks_off()
    ax.set_xlabel("radius the car actually drove at its slowest point, m (log scale)")
    ax.set_ylabel("slowest speed in the corner, km/h (log scale)")
    ax.set_title("Speed follows radius to the power %.2f" % fit["exponent"], fontsize=11.5)
    ax.text(0.03, 0.95, "orange: fitted line, 90%% interval %.2f-%.2f\nbar: fastest to slowest lap in that corner" % tuple(fit["exponent_ci90"]), transform=ax.transAxes, va="top", fontsize=8.2, color=INK2)
    # right: apex lateral g
    ax2 = fig.add_subplot(gs[0, 1])
    ap = np.array([r["apex_lat_g"] for r in rows]); pk = np.array([r["peak_lat_g"] for r in rows])
    order = np.argsort(ap)
    y = np.arange(len(rows))
    colour = [ORANGE if lab[i] in ("T9", "T15") else BLUE for i in order]
    ax2.barh(y, ap[order], color=colour, height=0.55, zorder=3)
    ax2.scatter(pk[order], y, s=26, marker="D", color=INK2, zorder=4, edgecolor=SURF, linewidth=0.8)
    for yy, i in zip(y, order):
        ax2.text(0.02, yy, "%s" % lab[i], va="center", ha="left", fontsize=8.2, color="white" if lum(colour[list(order).index(i)]) < 0.5 else INK, zorder=5, fontweight="bold")
        ax2.text(ap[i] - 0.015, yy, "%.2f" % ap[i], va="center", ha="right", fontsize=8, color="white", zorder=5)
    ax2.axvline(1.0, color=MUTED, lw=1.0, zorder=2)
    ax2.set_xlim(0, 1.45)
    ax2.set_yticks([])
    ax2.set_xlabel("sideways g: at the slowest point (bar), highest (diamond)")
    ax2.set_title("Grip used: 0.86-1.05 g, least at T9 and T15", fontsize=11.5)
    fig.suptitle("Corner speed is set by radius to the power one half", x=0.012, ha="left", y=0.975, fontsize=14, fontweight="bold")
    lede(fig, "At the grip limit v$^2$ = a$\\cdot$r, so speed grows with the square root of radius. Eleven corners from 23 m to 156 m of driven radius sit on a line with slope %.2f, "
              "and the sideways g at each slowest point stays between %.2f and %.2f g: the same tyres at the same limit, corner after corner." % (fit["exponent"], fit["apex_lat_g_min"], fit["apex_lat_g_max"]), 0.915)
    note(fig, "Radius = 1 / curvature at the slowest point (curvature from the gyro's yaw rate over speed, median of 13 m). T12 and T13 are near flat-out kinks where the radius is poorly defined; "
              "without them the slope is 0.52. Orange bars: the two corners that use the least grip.", width=185)
    return save(fig, figdir, "cornering_power_law.png")


# ------------------------------------------------------------------------------------------------ 4. exit speed -> time on the straight
def fig_exit(S, res, tables, figdir):
    wanted = [r["corner"] for r in res["exit_value"] if r["straight_is_clear"]]
    rows = {r["corner"]: r for r in res["exit_value"]}
    fig, axes = plt.subplots(1, len(wanted), figsize=(11.2, 5.0), squeeze=False, gridspec_kw=dict(wspace=0.28, left=0.07, right=0.985, top=0.70, bottom=0.18))
    axes = axes[0]
    for ax, label in zip(axes, wanted):
        r = rows[label]
        i_a = r["window_start_m"]; i_b = i_a + r["window_m"]
        x, y, lap = [], [], []
        for bb in range(S.nb):
            if S.fast[bb] and S.trusted_run(bb, i_a, i_b):
                tt = S.elapsed(bb, i_a, i_b)
                if tt is not None:
                    x.append(S.V[bb, i_a] * KMH); y.append(tt); lap.append(S.nums[bb])
        x = np.array(x); y = np.array(y)
        ax.scatter(x, y, s=46, color=BLUE, zorder=4, edgecolor=SURF, linewidth=1.0)
        for xi, yi, li in zip(x, y, lap):
            ax.annotate(str(li), (xi, yi), xytext=(4, 4), textcoords="offset points", fontsize=7.6, color=INK2)
        c = np.polyfit(x, y, 1)
        xx = np.array([x.min() - 1, x.max() + 1])
        ax.plot(xx, np.polyval(c, xx), color=ORANGE, lw=1.8, zorder=3)
        xm = float(np.median(x)); ym = float(np.polyval(c, xm))
        ax.plot(xx, ym - r["model_s_per_kmh"] * (xx - xm), color=INK2, lw=1.4, ls=(0, (4, 2.5)), zorder=3)
        lo_y, hi_y = ax.get_ylim()
        ax.set_ylim(lo_y - 0.26 * (hi_y - lo_y), hi_y)                  # a free band at the bottom for the numbers, clear of the dots
        ax.set_xlabel("speed 100 m after the apex, km/h")
        if ax is axes[0]:
            ax.set_ylabel("time for the next straight, s")
        ax.set_title("%s: the next %d m" % (label, r["window_m"]), fontsize=10.5)
        ax.text(0.03, 0.03, "laps: %.3f s per km/h\n(90%% interval %.3f to %.3f)\nphysics: %.3f s per km/h" % (-c[0], r.get("emp_lo", float("nan")), r.get("emp_hi", float("nan")), r["model_s_per_kmh"]),
                transform=ax.transAxes, ha="left", va="bottom", fontsize=8.0, color=INK2)
    fig.legend(handles=[plt.Line2D([0], [0], marker="o", color="none", markerfacecolor=BLUE, markersize=7, label="one lap (loop number beside it)"),
                        plt.Line2D([0], [0], color=ORANGE, lw=1.8, label="slope of these laps"),
                        plt.Line2D([0], [0], color=INK2, lw=1.4, ls=(0, (4, 2.5)), label="slope the physics predicts")], loc="lower center", ncol=3, bbox_to_anchor=(0.5, 0.045), fontsize=8.5)
    fig.suptitle("One km/h at the corner exit is worth about 0.01-0.03 s on the straight", x=0.012, ha="left", y=0.975, fontsize=14, fontweight="bold")
    lede(fig, "Drag grows with speed squared and power-limited acceleration falls as 1/speed, so a speed advantage at the exit decays along the straight but never vanishes. "
              "Dashed: what the engine's speed envelope predicts for the median lap. Orange: the slope across these laps. With 10-11 laps the intervals are wide, and the laps' slopes come out at a third to a half of the physics.", 0.915)
    note(fig, "Only straights that follow a single corner and contain no other corner are shown (T11, T14, T15). The window ends where the median lap starts braking for the next corner, or after 600 m.")
    return save(fig, figdir, "exit_speed_value.png")


# ------------------------------------------------------------------------------------------------ 5. case studies
def fig_case(S, lap, s0, s1, name, title, subtitle, figdir=".", ymin=None):
    b = lap - 1
    others = [c for c in range(S.nb) if c != b and S.fast[c] and S.clean[c]]
    x = np.arange(s0, s1 + 1)
    fig = plt.figure(figsize=(11.2, 6.9))
    gs = fig.add_gridspec(3, 1, height_ratios=[3.0, 1.45, 1.35], hspace=0.12, left=0.075, right=0.985, top=0.81, bottom=0.125)
    ax = fig.add_subplot(gs[0]); ax2 = fig.add_subplot(gs[1], sharex=ax); ax3 = fig.add_subplot(gs[2], sharex=ax)
    med = np.median(S.V[others][:, s0:s1 + 1], axis=0)
    for c in others:
        ax.plot(x, S.V[c, s0:s1 + 1] * KMH, color=CONTEXT, lw=0.9, zorder=2)
    ax.plot(x, med * KMH, color=BLUE, lw=2.0, zorder=4, label="median of the other clean loops")
    ax.plot(x, S.V[b, s0:s1 + 1] * KMH, color=ORANGE, lw=2.0, zorder=5, label="loop %d (%s)" % (lap, clock(S.time[b])))
    lo = float(min(S.V[b, s0:s1 + 1].min(), med.min()) * KMH)
    ax.set_ylim(ymin if ymin is not None else max(0.0, 10 * math.floor((lo - 8) / 10)), None)
    ax.set_ylabel("speed, km/h")
    ax.legend(loc="lower left", ncol=1, handlelength=2.0)
    tm = np.median(np.array([S.T[c, s0:s1 + 1] - S.T[c, s0] for c in others]), axis=0)
    tb = S.T[b, s0:s1 + 1] - S.T[b, s0]
    for c in others:
        ax2.plot(x, (S.T[c, s0:s1 + 1] - S.T[c, s0]) - tm, color=CONTEXT, lw=0.9, zorder=2)
    ax2.plot(x, tb - tm, color=ORANGE, lw=2.0, zorder=5)
    ax2.axhline(0, color=BLUE, lw=1.4, zorder=3)
    ax2.set_ylabel("time behind the\nmedian loop, s")
    ax2.text(x[0] + 12, 0.97, "%+.2f s behind the median loop by the end of this stretch" % (tb - tm)[-1], color=INK, fontsize=8.8, va="top", ha="left", fontweight="bold", transform=ax2.get_xaxis_transform())
    ax3.plot(x, S.LG[b, s0:s1 + 1], color=ORANGE, lw=1.4, zorder=5, label="forward g (braking is negative)")
    ax3.plot(x, np.abs(S.LAT[b, s0:s1 + 1]), color=INK2, lw=1.1, zorder=4, label="sideways g (size)")
    ax3.axhline(0, color=AXIS, lw=0.8)
    ax3.set_ylabel("g, loop %d" % lap)
    ax3.set_xlabel("distance round the loop, m")
    ax3.legend(loc="lower right", bbox_to_anchor=(1.0, 0.98), ncol=2, handlelength=2.0, borderaxespad=0.2)
    for a_ in (ax, ax2):
        a_.tick_params(labelbottom=False)
    ymax = ax.get_ylim()[1]
    for k in range(S.K):
        if s0 <= S.ia[k] <= s1:
            for a_ in (ax, ax2, ax3):
                a_.axvline(S.ia[k], color=AXIS, lw=0.9, zorder=1)
            ax.text(S.ia[k] + 3, ymax, S.labels[k], fontsize=8.6, color=INK2, va="top", ha="left", fontweight="bold")
    ax.set_xlim(s0, s1)
    fig.suptitle(title, x=0.012, ha="left", y=0.975, fontsize=14, fontweight="bold")
    lede(fig, subtitle, 0.925)
    note(fig, "Grey lines: every other clean loop. Speed from the GPS-fused channel, g from the phone's accelerometer (about 3% low). Vertical lines: the corners' reference apexes. "
              "A phone cannot tell a slide from a kerb strike from a lift for traffic; the driver can.")
    return save(fig, figdir, name)


def auto_cases(S, res, tables, n_cases=2):
    """Case studies: the biggest event on a clean loop, and the biggest on a different loop, each with a plain statement of what the numbers show."""
    anat = {(r["lap"], r["corner"]): r for r in tables["event_anatomy"]}
    out, used = [], set()
    for e in tables["biggest_losses"]:
        if len(out) >= n_cases:
            break
        if not e["clean_lap"] or e["lap"] in used:
            continue
        used.add(e["lap"])
        k = S.labels.index(e["corner"])
        a = anat.get((e["lap"], e["corner"]), {})
        parts = []
        if a.get("slowest_speed_vs_median_kmh") is not None:
            parts.append("slowest speed in the corner %+.1f km/h against the median of the other loops" % a["slowest_speed_vs_median_kmh"])
        if a.get("braked_earlier_by_m") is not None:
            parts.append("braking began %.1f m %s" % (abs(a["braked_earlier_by_m"]), "earlier" if a["braked_earlier_by_m"] > 0 else "later"))
        if a.get("peak_brake_vs_median_g") is not None:
            parts.append("peak braking %+.2f g" % a["peak_brake_vs_median_g"])
        if a.get("speed_100m_after_vs_median_kmh") is not None:
            parts.append("speed 100 m after the apex %+.1f km/h" % a["speed_100m_after_vs_median_kmh"])
        sub = ("The stretch around %s cost this loop %.2f s against the median loop (window %d-%d m). At %s: %s. The panels show 900 m of track around the corner." % (e["corner"], e["excess_s"], e["window_start_m"], e["window_end_m"], e["corner"], "; ".join(parts))
               if parts else "The stretch around %s cost this loop %.2f s." % (e["corner"], e["excess_s"]))
        out.append(dict(lap=e["lap"], s0=max(0, S.ia[k] - 450), s1=min(S.n, S.ia[k] + 450), name="case_loop%d_%s.png" % (e["lap"], e["corner"]),
                        title="Loop %d lost %.1f s around %s" % (e["lap"], e["excess_s"], e["corner"]), subtitle=sub))
    return out


# ------------------------------------------------------------------------------------------------ 6. three laws, real points (for docs)
def fig_three_laws(S, res, tables, figdir):
    import ultimate as U
    fig = plt.figure(figsize=(11.2, 4.7))
    gs = fig.add_gridspec(1, 3, wspace=0.30, left=0.065, right=0.985, top=0.74, bottom=0.145)
    # (a) braking: distance against drop in speed squared
    ax = fig.add_subplot(gs[0, 0])
    z = tables["braking_zones"]
    dv2 = np.array([(r["v_on_kmh"] / KMH) ** 2 - (r["v_min_kmh"] / KMH) ** 2 for r in z]); d = np.array([r["span_m"] for r in z])
    ax.scatter(dv2, d, s=15, color=BLUE, alpha=0.85, zorder=4, edgecolor="none")
    xx = np.array([0, dv2.max() * 1.05])
    ytop = d.max() * 1.05
    for g_ in (0.6, 0.9, 1.2):
        ax.plot(xx, xx / (2 * g_ * G0), color=MUTED, lw=0.9, zorder=2)
        xl = min(xx[1], ytop * 2 * g_ * G0)
        if xl == xx[1]:
            ax.text(xl, xl / (2 * g_ * G0), " %.1f g" % g_, fontsize=8, color=MUTED, va="center")
        else:
            ax.text(xl * 0.985, ytop * 0.985, "%.1f g " % g_, fontsize=8, color=MUTED, va="top", ha="right")
    ax.set_xlim(0, dv2.max() * 1.18); ax.set_ylim(0, ytop)
    ax.set_xlabel("fall in speed$^2$ over the zone, (m/s)$^2$")
    ax.set_ylabel("braking distance, m")
    ax.set_title("Braking: distance = $\\Delta v^2$ / 2a", fontsize=10.5)
    # (b) cornering: lateral acceleration at the slowest point against speed squared / radius -> speed vs sqrt(r) linear
    ax = fig.add_subplot(gs[0, 1])
    rows = tables["corner_speed_by_corner"]
    sr = np.array([math.sqrt(r["radius_m"]) for r in rows]); v = np.array([r["v_min_kmh"] for r in rows])
    ax.scatter(sr, v, s=36, color=BLUE, zorder=4, edgecolor=SURF, linewidth=0.9)
    for s_, v_, r in zip(sr, v, rows):
        if r["corner"] in ("T9", "T11", "T7", "T14", "T5", "T13"):
            ax.annotate(r["corner"], (s_, v_), xytext=(5, -9) if r["corner"] in ("T9", "T5") else (5, 4), textcoords="offset points", fontsize=7.6, color=INK2)
    xx = np.array([0, sr.max() * 1.05])
    for g_ in (0.8, 1.0):
        ax.plot(xx, np.sqrt(g_ * G0) * xx * KMH, color=MUTED, lw=0.9, zorder=2)
        ax.text(xx[1], np.sqrt(g_ * G0) * xx[1] * KMH, " %.1f g" % g_, fontsize=8, color=MUTED, va="center")
    ax.set_xlim(0, sr.max() * 1.2); ax.set_ylim(0, v.max() * 1.1)
    ax.set_xlabel("square root of the driven radius, $\\sqrt{\\mathrm{m}}$")
    ax.set_ylabel("slowest speed in the corner, km/h")
    ax.set_title("Cornering: v = $\\sqrt{a\\cdot r}$", fontsize=10.5)
    # (c) straight: acceleration against speed
    ax = fig.add_subplot(gs[0, 2])
    env = S.ult.env
    fl = np.where(S.fast & S.clean)[0]
    n = S.n
    vv = S.V[fl][:, :n + 1].ravel() * KMH; lg = S.LG[fl][:, :n + 1].ravel(); lat = np.abs(S.LAT[fl][:, :n + 1].ravel())
    m = (lat < 0.12) & (lg > 0.02) & (vv > 90)
    bins = np.arange(90, 255, 15)
    cx, cy = [], []
    for lo in bins:
        s_ = m & (vv >= lo) & (vv < lo + 15)
        if s_.sum() >= 30:
            cx.append(np.median(vv[s_])); cy.append(np.median(lg[s_]))
    ax.scatter(cx, cy, s=40, color=BLUE, zorder=4, edgecolor=SURF, linewidth=0.9, label="median of the laps' straights")
    xs = np.linspace(95, 250, 80) / KMH
    pw, kk = env["pw"], env["k"]
    ax.plot(xs * KMH, (pw / xs - kk * xs ** 2) / G0, color=ORANGE, lw=1.8, zorder=3, label="engine $-$ drag:  pw/v $-$ k v$^2$")
    ax.plot(xs * KMH, (pw / xs) / G0, color=MUTED, lw=1.0, ls=(0, (4, 2.5)), zorder=2)
    ax.plot(xs * KMH, (kk * xs ** 2) / G0, color=MUTED, lw=1.0, ls=(0, (1, 2)), zorder=2)
    ax.text(209, (pw / (209 / KMH)) / G0 + 0.065, "power-limited push, $\\propto$ 1/v", fontsize=7.6, color=MUTED, ha="center", va="bottom")
    ax.text(205, (kk * (205 / KMH) ** 2) / G0 + 0.02, "drag, $\\propto$ v$^2$", fontsize=7.8, color=MUTED, ha="center", va="bottom")
    ax.set_xlim(90, 255); ax.set_ylim(0, 0.8)
    ax.set_xlabel("speed, km/h")
    ax.set_ylabel("forward acceleration, g")
    ax.set_title("Straights: a = P/(m v) $-$ k v$^2$", fontsize=10.5)
    ax.legend(loc="upper right", fontsize=7.6, handlelength=1.6)
    fig.suptitle("The three laws, with the Sepang laps sitting on them", x=0.012, ha="left", y=0.975, fontsize=14, fontweight="bold")
    lede(fig, "Left: each dot is one lap's braking zone (%d of them); the lines are constant deceleration. Middle: each dot is one corner; the lines are constant sideways g. "
              "Right: median forward acceleration of the clean laps on near-straight track; the curve is the engine's fitted speed envelope." % len(d), 0.915)
    return save(fig, figdir, "three_laws_real_points.png")


# ------------------------------------------------------------------------------------------------ driver
def make_all(S, res, tables, extra, figdir):
    style()
    os.makedirs(figdir, exist_ok=True)
    out = [fig_time_map(S, res, extra, figdir), fig_braking(S, res, tables, figdir), fig_cornering(S, res, tables, figdir),
           fig_exit(S, res, tables, figdir), fig_three_laws(S, res, tables, figdir)]
    for c in auto_cases(S, res, tables):
        out.append(fig_case(S, figdir=figdir, **c))
    return out
