#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Report writer for ultimate.py: markdown, CSV and figures from the result of ultimate.run()."""
import csv
import datetime
import math
import os

import numpy as np

import ultimate as U


def fmt_t(t):
    if not np.isfinite(t):
        return "-"
    return "%d:%06.3f" % (int(t // 60), t % 60)


def fmt_s(x, signed=True):
    if not np.isfinite(x):
        return "-"
    return ("%+.3f" if signed else "%.3f") % x


def lap_labels(laps):
    """'A3' = third full loop (cut 0 to cut 0) of the first file, 'B1' the first of the second, ..."""
    names, count = [], {}
    sessions = []
    for l in laps:
        if l["session"] not in sessions:
            sessions.append(l["session"])
    for l in laps:
        i = sessions.index(l["session"])
        count[i] = count.get(i, 0) + 1
        names.append("%s%d" % (chr(ord("A") + i % 26), count[i]))
    return names, sessions


def _plt():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.size": 9, "axes.grid": True, "grid.alpha": 0.25, "axes.spines.top": False,
                         "axes.spines.right": False, "figure.dpi": 110})
    return plt


PALETTE = ["#1b6ca8", "#d1495b", "#edae49", "#00798c", "#66a182", "#8f5fa8", "#c76f3c"]


# ------------------------------------------------------------------------------------------------ numbers
def summarise(R):
    prep, ult, E = R["prep"], R["ult"], R["E"]
    laps = prep["laps"]
    names, sessions = lap_labels(laps)
    clean = [b for b, l in enumerate(laps) if l.get("clean")]
    fast = [b for b, l in enumerate(laps) if l.get("fast")]
    best_b = min(fast, key=lambda b: laps[b]["time"])
    gap = ult.lap_gap()
    S = dict(names=names, sessions=sessions, clean=clean, fast=fast, best=best_b, gap=gap,
             best_time=laps[best_b]["time"], comp=ult.comp, comp_raw=ult.comp_raw, comp_sd=ult.comp_sd)
    G = ult.gaps()
    S["sec_mean_gap"] = np.array([np.nanmean(G[ult.ok[:, i], i]) if ult.ok[:, i].any() else np.nan for i in range(ult.M)])
    S["sec_typ"] = np.array([np.nanmedian(ult.E[ult.ok[:, i], i]) for i in range(ult.M)])
    return S


# ------------------------------------------------------------------------------------------------ CSV
def write_csv(R, S, out):
    prep, ult, E = R["prep"], R["ult"], R["E"]
    laps, names = prep["laps"], S["names"]
    G = ult.gaps()
    with open(os.path.join(out, "ultimate_sections.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["lap", "session", "loop_time_s", "fast", "clean"] + ["%s_%s" % (R["names"][i], k) for i in range(ult.M) for k in ("time_s", "value_s", "gap_s")])
        for b, l in enumerate(laps):
            row = [names[b], l["session"], "%.3f" % l["time"], int(bool(l.get("fast"))), int(bool(l.get("clean")))]
            for i in range(ult.M):
                if ult.ok[b, i]:
                    row += ["%.3f" % ult.T[b, i], "%.3f" % ult.E[b, i], "%.3f" % G[b, i]]
                else:
                    row += ["", "", ""]
            w.writerow(row)
    keys = U.EVENT_KEYS
    with open(os.path.join(out, "ultimate_corners.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["lap", "corner", "linked"] + keys + ["d_" + k for k in keys])
        for b, l in enumerate(laps):
            for k, c in enumerate(R["corners"]):
                if not ult.ok[b, E["sec"][k]]:
                    continue
                ev = E["ev"][b][k]
                vals = []
                for key in keys:
                    x = ev[key]
                    vals.append("" if not np.isfinite(x) else "%.3f" % (x * 3.6 if key in ("v_min", "v_brake", "v_exit100") else x))
                dl = []
                for key in keys:
                    x = R["deltas"][key][b, k]
                    dl.append("" if not np.isfinite(x) else "%.3f" % (x * 3.6 if key in ("v_min", "v_brake", "v_exit100") else x))
                w.writerow([names[b], c["label"], int(E["linked"][k])] + vals + dl)


# ------------------------------------------------------------------------------------------------ figures
def fig_map(R, S, path):
    plt = _plt()
    prep, ult = R["prep"], R["ult"]
    ref = prep["ref"]
    fig, ax = plt.subplots(figsize=(9.2, 6.2))
    cuts = prep["cuts"]
    L = ref.L
    bounds = [(c - cuts[0]) % L for c in cuts] + [L]
    pts = np.arange(ref.n) * ref.ds
    rel = (pts - cuts[0]) % L
    for i in range(ult.M):
        m = (rel >= bounds[i]) & (rel < bounds[i + 1])
        idx = np.where(m)[0]
        idx = idx[np.argsort(rel[idx])]
        ax.plot(ref.xy[idx, 0], ref.xy[idx, 1], color=PALETTE[i % len(PALETTE)], lw=3.2, solid_capstyle="butt",
                label="%s  (%s)" % (R["names"][i], fmt_s(S["sec_mean_gap"][i], True) + " s avg gap"))
    for c in R["corners"]:
        x, y = ref.point(np.array([cuts[0] + c["rel"]]))
        ax.annotate(c["label"], (x[0], y[0]), fontsize=8, ha="center", va="center",
                    bbox=dict(boxstyle="round,pad=0.15", fc="white", ec="0.5", lw=0.5, alpha=0.9))
    for c in cuts:
        x, y = ref.point(np.array([c]))
        ax.plot(x, y, "k|", ms=9, mew=1.5)
    x0, y0 = ref.point(np.array([cuts[0]]))
    ax.plot(x0, y0, "ko", ms=7)
    ax.annotate("lap starts/ends here\n(mid front straight)", (x0[0], y0[0]), xytext=(-70, 18), textcoords="offset points",
                fontsize=7.5, arrowprops=dict(arrowstyle="-", lw=0.6))
    ax.set_aspect("equal")
    ax.grid(False)
    ax.set_xticks([])
    ax.set_yticks([])
    for sp in ax.spines.values():
        sp.set_visible(False)
    ax.set_title("%s\nsections are cut at the middle of each straight (| marks)" % R["info"]["layout"], fontsize=10)
    ax.legend(loc="center left", bbox_to_anchor=(1.0, 0.5), fontsize=7.5, frameon=False,
              title="section (average gap\nto the ultimate lap)", title_fontsize=8)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_delta(R, S, path):
    plt = _plt()
    prep, ult = R["prep"], R["ult"]
    laps = prep["laps"]
    p = laps[0]["al"]["p"]
    t_u = ult.t_ult
    cl = S["clean"]
    fig, (ax, ax2) = plt.subplots(2, 1, figsize=(11, 6.4), sharex=True, gridspec_kw=dict(height_ratios=[3, 1.2]))
    cmap = plt.get_cmap("viridis")
    for j, b in enumerate(cl):
        al = laps[b]["al"]
        d = al["t"] - t_u
        is_best = b == S["best"]
        ax.plot(p / 1000.0, d, color="k" if is_best else cmap(j / max(len(cl) - 1, 1)), lw=2.2 if is_best else 1.2,
                label="%s  %s  (%+.2f s)%s" % (S["names"][b], fmt_t(laps[b]["time"]), d[-1], "  best lap" if is_best else ""))
    ax.axhline(0, color="0.2", lw=1.0)
    L = p[-1]
    cr = list(ult.cuts_rel) + [L]
    for i in range(ult.M):
        ax.axvspan(cr[i] / 1000.0, cr[i + 1] / 1000.0, color=PALETTE[i % len(PALETTE)], alpha=0.07)
        ax.text((cr[i] + cr[i + 1]) / 2000.0, 0.99, R["names"][i], transform=ax.get_xaxis_transform(), ha="center", va="top",
                fontsize=8, color=PALETTE[i % len(PALETTE)], fontweight="bold")
    ax.set_ylabel("time behind the ultimate lap (s)")
    ax.set_title("Where each lap loses time to the ultimate lap (%s, +-%.2f s)" % (fmt_t(ult.comp), ult.comp_sd), fontsize=10)
    ax.legend(fontsize=7.5, ncol=2, loc="upper left", bbox_to_anchor=(0.0, 0.93), frameon=False)
    ax2.plot(p / 1000.0, np.interp(p, p, np.interp(p, laps[S["best"]]["al"]["p"], laps[S["best"]]["al"]["v"])) * 3.6, color="0.25", lw=1.1)
    for c in R["corners"]:
        ax2.axvline(c["rel"] / 1000.0, color="0.7", lw=0.5)
        ax2.text(c["rel"] / 1000.0, 1.02, c["label"], transform=ax2.get_xaxis_transform(), ha="center", fontsize=6.5, color="0.35")
    ax2.set_ylabel("best lap speed (km/h)")
    ax2.set_xlabel("distance from the middle of the front straight (km)")
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_sections(R, S, path):
    plt = _plt()
    prep, ult = R["prep"], R["ult"]
    laps = prep["laps"]
    cl = [b for b in S["fast"]]
    G = ult.gaps()[cl, :]
    fig, ax = plt.subplots(figsize=(8.5, 0.5 * len(cl) + 2.4))
    vmax = max(0.3, float(np.nanpercentile(np.abs(G), 95)))
    im = ax.imshow(np.where(np.isfinite(G), G, np.nan), cmap="RdBu_r", vmin=-vmax, vmax=vmax, aspect="auto")
    ax.grid(False)
    for i in range(G.shape[0]):
        for j in range(G.shape[1]):
            v = G[i, j]
            ax.text(j, i, "-" if not np.isfinite(v) else ("%.2f" % v if abs(v) >= 0.005 else "0"), ha="center", va="center",
                    fontsize=8, color="white" if np.isfinite(v) and abs(v) > 0.6 * vmax else "black")
    ax.set_xticks(range(ult.M))
    ax.set_xticklabels(R["names"])
    ax.set_yticks(range(len(cl)))
    ax.set_yticklabels(["%s  %s" % (S["names"][b], fmt_t(laps[b]["time"])) for b in cl])
    ax.set_title("Time lost to the ultimate lap, by section (s).  0 = this lap supplies the ultimate lap's section", fontsize=10)
    fig.colorbar(im, ax=ax, fraction=0.03, pad=0.02)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_inputs(R, S, path):
    """Typical lap minus the ultimate lap's source lap, corner by corner."""
    plt = _plt()
    ult, E = R["ult"], R["E"]
    K = E["K"]
    labels = [c["label"] for c in R["corners"]]
    cl = S["clean"]
    specs = [("v_min", "min speed (km/h)", 3.6), ("brake_pos", "brake point (m)\n+ = earlier", -1.0),
             ("brake_peak", "peak brake (g)", 1.0), ("pickup", "throttle (m)\n+ = later", 1.0),
             ("v_exit100", "speed +100 m (km/h)", 3.6)]
    fig, axs = plt.subplots(len(specs), 1, figsize=(10, 1.9 * len(specs)), sharex=True)
    for ax, (key, title, sc) in zip(axs, specs):
        med = np.full(K, np.nan)
        lo = np.full(K, np.nan)
        hi = np.full(K, np.nan)
        for k in range(K):
            v = [R["deltas"][key][b, k] for b in cl if np.isfinite(R["deltas"][key][b, k]) and b != ult.pick[E["sec"][k]]]
            if v:
                med[k] = np.median(v) * sc
                lo[k] = np.min(v) * sc
                hi[k] = np.max(v) * sc
        x = np.arange(K)
        ax.bar(x, med, color=[PALETTE[E["sec"][k] % len(PALETTE)] for k in range(K)], alpha=0.85, width=0.7)
        ax.vlines(x, lo, hi, color="0.3", lw=1.0)
        ax.axhline(0, color="0.2", lw=0.8)
        ax.set_ylabel(title, fontsize=7.5)
        vals = np.abs(np.r_[med[np.isfinite(med)], 0.0])
        lim = max(3.0 * float(np.percentile(vals, 85)), {"v_min": 2.0, "brake_pos": 8.0, "brake_peak": 0.1, "pickup": 4.0,
                                                          "v_exit100": 3.0}[key])
        ax.set_ylim(-lim, lim)                                    # a lone far-out lap does not squash the rest
    axs[-1].set_xticks(range(K))
    axs[-1].set_xticklabels(labels)
    axs[0].set_title("Typical lap minus the ultimate lap's source lap (bar = median, line = range of your laps).  "
                     "Positive brake/pick-up = earlier/later than your best", fontsize=9)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def fig_predictor(R, S, path):
    plt = _plt()
    pr = R["pred"]
    if pr is None:
        return False
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 4))
    cps = 100 * pr["cps"]
    lab = {"B0": "B0  mean of other laps (no look at this lap)", "M0": "M0  same deviation from the typical lap to the end",
           "M1": "M1  typical time-to-go scaled by pace", "M2": "M2  M0 with a fitted slope", "M3": "M3  average of M1 and M2"}
    for m, c in zip(("B0", "M0", "M1", "M2", "M3"), ("0.6", "#1b6ca8", "#d1495b", "#00798c", "#111111")):
        a1.plot(cps, pr["rmse"][m], "-o", ms=3, color=c, label=lab[m], lw=1.6 if m != "B0" else 1.2)
    a1.set_xlabel("fraction of the lap completed (%)")
    a1.set_ylabel("RMS error of the predicted lap time (s)")
    a1.set_title("How early can the lap time be predicted? (%d laps, leave-one-out, causal GPS)" % pr["n"], fontsize=9)
    a1.legend(fontsize=7, frameon=False)
    b = pr["ids"][-1]
    a2.plot(cps, pr["pred"]["M0"][b], "-o", ms=3, color="#1b6ca8", label="M0")
    a2.plot(cps, pr["pred"]["M3"][b], "-o", ms=3, color="#111111", label="M3")
    if b in pr["potential"]:
        a2.plot(cps, pr["potential"][b], "--", color="#edae49", label="potential (rest of the lap driven like the ultimate lap)")
    a2.axhline(pr["final"][b], color="k", lw=1.0, ls=":", label="final time %s" % fmt_t(pr["final"][b]))
    a2.set_xlabel("fraction of the lap completed (%)")
    a2.set_ylabel("predicted lap time (s)")
    a2.set_title("Running prediction during lap %s" % S["names"][b], fontsize=9)
    a2.legend(fontsize=7, frameon=False)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)
    return True


# ------------------------------------------------------------------------------------------------ markdown
def phase_note(key, d):
    """One short phrase for a delta (typical or latest lap minus the source lap)."""
    if not np.isfinite(d):
        return None
    if key == "v_min":
        return "%.1f km/h %s through the slowest point" % (abs(d * 3.6), "slower" if d < 0 else "faster")
    if key == "brake_pos":
        return "braked %.0f m %s" % (abs(d), "earlier" if d < 0 else "later")
    if key == "brake_peak":
        return "peak braking %.2f g %s" % (abs(d), "lower" if d < 0 else "higher")
    if key == "pickup":
        return "throttle %.0f m %s after the apex" % (abs(d), "later" if d > 0 else "earlier")
    if key == "v_exit100":
        return "%.1f km/h %s 100 m after the apex" % (abs(d * 3.6), "slower" if d < 0 else "faster")
    return None


# metric scale: a difference this large is worth looking at (about 3x the measurement noise seen in simulation, never below
# what a driver can feel; braking points get more room, because a phone resolves them less well on a real car than in simulation)
NOTICE = dict(v_min=1.0 / 3.6 * 2.0, brake_pos=10.0, brake_peak=0.08, pickup=6.0, v_exit100=1.0 / 3.6 * 2.5)


# a metric that jumps about from lap to lap at one corner (a throttle pick-up of 2 m on one lap and 80 m on the next, because the
# 0.10 g threshold is crossed at light throttle) says nothing about any one lap, so no note is made about it there
SPREAD_MAX = dict(pickup=25.0)


def unstable(R, S, k, key):
    """True when `key` at corner k has a lap-to-lap interquartile range above SPREAD_MAX (needs 4+ clean loops)."""
    lim = SPREAD_MAX.get(key)
    if lim is None:
        return False
    v = [R["E"]["ev"][x][k][key] for x in S["clean"]]
    v = [t for t in v if np.isfinite(t)]
    if len(v) < 4:
        return False
    q1, q3 = np.percentile(v, [25, 75])
    return bool(q3 - q1 > lim)


def coaching_lines(R, S, b, n=5, vs_typical=False):
    """Largest differences between lap b (or the typical clean lap) and the ultimate lap's source laps, by size/notice level."""
    ult, E = R["ult"], R["E"]
    items = []
    for k, c in enumerate(R["corners"]):
        sec = E["sec"][k]
        for key in ("v_min", "brake_pos", "brake_peak", "pickup", "v_exit100"):
            if E["linked"][k]:                  # no speed minimum of its own: the speed there is a place on a slope, too noisy to coach on
                continue
            if unstable(R, S, k, key):
                continue
            if vs_typical:
                v = [R["deltas"][key][x, k] for x in S["clean"] if np.isfinite(R["deltas"][key][x, k]) and x != ult.pick[sec]]
                d = float(np.median(v)) if len(v) >= 2 else float("nan")
            else:
                d = R["deltas"][key][b, k]
            if not np.isfinite(d):
                continue
            # brake_pos is negative metres before the apex: earlier = more negative
            score = abs(d) / NOTICE[key]
            bad = (key == "v_min" and d < 0) or (key == "brake_pos" and d < 0) or (key == "brake_peak" and d < 0) or \
                  (key == "pickup" and d > 0) or (key == "v_exit100" and d < 0)
            if score >= 1.0 and bad:
                items.append((score, c["label"], phase_note(key, d), key))
    items.sort(key=lambda x: -x[0])
    return items[:n]


def write_md(R, S, out, files, argv_note=""):
    prep, ult, E, info = R["prep"], R["ult"], R["E"], R["info"]
    laps, names = prep["laps"], S["names"]
    G = ult.gaps()
    M = ult.M
    L = []
    w = L.append

    def img(key):                               # a figure line only when that figure was drawn (--no-plots, failed figure)
        if key in files:
            w("![%s](%s)" % (key, files[key]))

    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    w("# Ultimate lap report")
    w("")
    w("%s - %d file(s), %d complete loops (%d fast, %d clean through every section). Generated %s." % (
        info["layout"], len(S["sessions"]), len(laps), len(S["fast"]), len(S["clean"]), now))
    w("")
    if prep.get("skipped"):
        w("> **Left out because they could not be used:** " + "; ".join("%s (%s)" % tuple(s) for s in prep["skipped"]))
        w("")
    for msg in prep.get("warnings", []):
        w("> **Warning:** " + msg)
        w("")
    if not S["clean"]:
        w("> **No loop is clean through every section** (a clean loop has a GPS fix and racing speed at every section boundary). "
          "The ultimate lap below is stitched from partial loops: it can come out slower than your fastest loop and is less "
          "reliable than its error bar says. Treat the tables as indications only, or record a session with fewer GPS dropouts.")
        w("")
    w("## 1. Summary")
    w("")
    w("| | |")
    w("|---|---|")
    w("| Your fastest loop | **%s** (%s) |" % (fmt_t(S["best_time"]), names[S["best"]]))
    w("| Ultimate lap (best demonstrated section of each kind, noise-corrected) | **%s** +-%.2f s |" % (fmt_t(ult.comp), ult.comp_sd))
    w("| ... before the noise correction | %s |" % fmt_t(ult.comp_raw))
    gain = max(S["best_time"] - ult.comp, 0.0)         # the ultimate lap is a steady-state lap (speed handed over at the start/finish cut), so it
                                                       # can sit slightly slower than the as-flown fastest loop, which began and ended at other speeds
    slower = ult.comp - S["best_time"]
    if S["clean"] and S["best"] not in S["clean"] and slower > 0.15:
        # the fastest loop could not supply all its sections (a GPS dropout or a slow cut speed), so the ultimate lap is built without
        # some of its best pieces and understates what you have shown
        w("| Gain over your fastest loop | not available: your fastest loop is not clean through every section (a GPS dropout, most likely), so "
          "parts of it cannot be used and the ultimate lap comes out %.2f s slower than it. Take your fastest loop as the best you have shown. |" % slower)
        typ = float(np.median([laps[b]["time"] for b in S["clean"]]))
        w("| Typical clean loop | %s (%.2f s slower than the ultimate lap) |" % (fmt_t(typ), max(typ - ult.comp, 0.0)))
    elif S["clean"]:
        w("| Gain over your fastest loop | %.2f s%s |" % (gain, "  (within the error bar: your fastest loop is already about as good as your best pieces)"
                                                       if gain < 0.5 * ult.comp_sd else ""))
        typ = float(np.median([laps[b]["time"] for b in S["clean"]]))
        w("| Typical clean loop | %s (%.2f s slower than the ultimate lap) |" % (fmt_t(typ), max(typ - ult.comp, 0.0)))
    else:
        w("| Gain over your fastest loop | not available: no loop is clean through every section |")
        w("| Typical clean loop | - |")
    gsc = prep.get("gps_scatter")
    if gsc is not None:
        w("| GPS quality (sideways scatter of fixes on the straights) | %.1f m - %s |" % (
            gsc, "good" if gsc < 2.5 else ("typical for a phone" if gsc < 3.5 else "noisy: differences under about 0.15 s are not trustworthy")))
    if info["registered"]:
        pr = info["model"]["meta"]["prior"]["lap_time_s"]
        w("| Physics reference (a simple point-mass model on a perfect line, grip mu 1.1 / 1.2 / 1.3) | %s / %s / %s - a rough, unvalidated "
          "ceiling; real cars are usually slower, so it is not a target |" % (fmt_t(pr["1.1"]), fmt_t(pr["1.2"]), fmt_t(pr["1.3"])))
    w("")
    if len(S["fast"]) < 2:
        w("> **Only one usable loop in this data, so there is nothing to stitch yet.** The ultimate lap is that loop, give or take the "
          "error bar, and every gap below is zero. The comparison starts to say something from the second flying lap on. You can also "
          "give the program several files at once to pool the sessions of the day.")
        w("")
    # where the time goes
    order = [int(i) for i in np.argsort(-np.nan_to_num(S["sec_mean_gap"], nan=-1)) if np.isfinite(S["sec_mean_gap"][i])]
    if order and len(S["fast"]) >= 2:
        w("**Where the time is, on average over the loops that have a good fix in each section (gap to the ultimate lap):** " + ", ".join(
            "%s %.2f s" % (R["names"][i], S["sec_mean_gap"][i]) for i in order[:4]) + ".")
        w("")
    lines = coaching_lines(R, S, None, n=6, vs_typical=True)
    if lines:
        w("**What differs most between a typical loop and the ultimate lap's source laps:**")
        w("")
        for sc, lab, ph, key in lines:
            w("- %s: %s" % (lab, ph))
        w("")
    img("map")
    w("")
    w("## 2. The ultimate lap, section by section")
    w("")
    w("A section runs from the middle of one straight to the middle of the next. The time of a section is corrected for the "
       "speed the lap carried over the cut (a lap that leaves a corner faster starts the next section faster; the correction is "
       "the *price* in the table, seconds per m/s). The ultimate lap is the sum of the best corrected sections.")
    w("")
    w("| Section | Corners | Source lap | Value (s) | Typical loop (s) | Gain vs typical (s) | Price (s per m/s) | Spread of your laps (s) |")
    w("|---|---|---|---|---|---|---|---|")
    for i in range(M):
        b = ult.pick[i]
        w("| %d | %s | %s | %.3f | %.3f | %.3f | %.3f | %.2f |" % (
            i + 1, R["names"][i], names[b], ult.sec[i], S["sec_typ"][i], S["sec_typ"][i] - ult.sec[i], ult.price[i],
            math.sqrt(max(ult.V[i], 0.0))))
    w("| | | | **%.3f** | **%.3f** | **%.3f** | | |" % (ult.comp, np.nansum(S["sec_typ"]), np.nansum(S["sec_typ"]) - ult.comp))
    w("")
    img("sections")
    w("")
    w("## 3. Every loop against the ultimate lap")
    w("")
    w("| Loop | Time | Gap | " + " | ".join(R["names"]) + " | Note |")
    w("|---|---|---|" + "---|" * M + "---|")
    for b, l in enumerate(laps):
        note = "" if l.get("clean") else ("not clean through every section" if l.get("fast") else "in/out/slow loop - not used")
        cells = [fmt_s(G[b, i]) if ult.ok[b, i] else "-" for i in range(M)]
        gp = S["gap"][b]
        w("| %s | %s | %s | %s | %s |" % (names[b], fmt_t(l["time"]), fmt_s(gp) if np.isfinite(gp) else "-", " | ".join(cells), note))
    w("")
    w("(Loops start and end in the middle of the front straight, so they are offset from the timing-line laps you see on a dash.)")
    w("")
    img("delta")
    w("")
    w("## 4. Driver inputs, corner by corner")
    w("")
    w("Values of the loop that supplies the ultimate lap's section (the *source*), and how a typical loop differs. *Braking from* is "
       "how far before the corner's reference apex the main braking zone starts (deceleration first beyond 0.30 g, or beyond 35 % "
       "of that zone's peak if that is more; the reference apex is the map's apex, moved to where your own laps run slowest when "
       "that is more than 8 m away), so it is a "
       "position on the track you can compare lap to lap; peak brake is the strongest 0.2 s of braking, not the best instant (a phone in "
       "its mount rings at 2-10 Hz, which moves a single instant by 0.1-0.2 g from lap to lap); pick-up is how far after the slowest point "
       "forward acceleration first exceeds 0.10 g (blank if that does not happen before the next corner's braking zone starts); "
       "speed +100 m is left blank where the next corner is closer than 100 m or its braking zone starts within 25 m of that point "
       "(it would then measure how early each lap braked for the next corner). A lift that sheds under 15 km/h is not counted as a braking zone. Corners that never reach a speed "
       "minimum of their own (linked) show only cornering and speed. Braking points are the least certain numbers here: a phone "
       "senses braking only through acceleration, and lifting off at high speed already slows a car. Use them to check "
       "repeatability, and move a braking point later only a few metres at a time and only while the car stays settled.")
    w("")
    w("| Corner | Src | Min speed | Braking from | Peak brake | Pick-up | Speed +100 m | Peak lat | Typical vs source |")
    w("|---|---|---|---|---|---|---|---|---|")
    any_wob = False
    for k, c in enumerate(R["corners"]):
        b = ult.pick[E["sec"][k]]
        ev = E["ev"][b][k]
        td = []
        wob = False
        for key in ("v_min", "brake_pos", "brake_peak", "pickup", "v_exit100"):
            v = [R["deltas"][key][x, k] for x in S["clean"] if np.isfinite(R["deltas"][key][x, k]) and x != b]
            if key == "pickup" and not E["linked"][k] and unstable(R, S, k, key):
                wob = True
                continue
            if len(v) >= 2 and not E["linked"][k]:
                ph = phase_note(key, float(np.median(v)))
                if ph and abs(float(np.median(v))) >= NOTICE[key]:
                    td.append(ph)
        n_cmp = len([x for x in S["clean"] if x != b])          # other clean loops to compare with the source loop
        any_wob = any_wob or wob
        w("| %s%s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            c["label"], " (linked)" if E["linked"][k] else "", names[b],
            "%.0f km/h" % (ev["v_min"] * 3.6) if np.isfinite(ev["v_min"]) else "-",
            "%.0f m" % -ev["brake_pos"] if np.isfinite(ev["brake_pos"]) else "-",
            "%.2f g" % ev["brake_peak"] if np.isfinite(ev["brake_peak"]) else "-",
            ("%.0f m" % ev["pickup"] + ("*" if wob else "")) if np.isfinite(ev["pickup"]) else "-",
            "%.0f km/h" % (ev["v_exit100"] * 3.6) if np.isfinite(ev["v_exit100"]) else "-",
            "%.2f g" % ev["lat_peak"] if np.isfinite(ev["lat_peak"]) else "-",
            "- (no speed minimum of its own)" if E["linked"][k] else
            ("; ".join(td) if td else ("similar" if n_cmp >= 2 else "- (needs 3+ clean loops)"))))
    w("")
    if any_wob:
        w("\\* The throttle pick-up at this corner varies by more than 25 m from lap to lap (the 0.10 g threshold is crossed at light "
          "throttle, so a small change in throttle moves it a long way): the value is shown, but no comparison is made.")
        w("")
    img("inputs")
    w("")
    if S["clean"]:
        b = S["clean"][-1]
        w("### Latest clean loop (%s, %s): differences against the ultimate lap's source laps" % (names[b], fmt_t(laps[b]["time"])))
        w("")
        ls = coaching_lines(R, S, b, n=8)
        if len(S["clean"]) < 2:
            w("- Only one clean loop so far, so there is nothing to compare it with yet.")
        elif ls:
            for sc, lab, ph, key in ls:
                w("- %s: %s" % (lab, ph))
        elif all(int(x) == b for x in ult.pick):
            w("- this loop supplies every piece of the ultimate lap, so there are no differences to list.")
        else:
            w("- nothing beyond the noise level: this loop is close to your best in every corner.")
        w("")
    w("## 5. Predicting the lap while it is being driven")
    w("")
    if R["pred"] is not None:
        pr = R["pred"]
        w("Each clean loop was replayed the way a live display would have seen it: GPS fixes arrive late and only the part of the lap "
          "driven so far is used. The typical lap it is compared with is built from all the *other* loops, including later ones, which "
          "a live display would not have yet (a small advantage in the simulated tests). RMS error of the predicted loop time (s):")
        w("")
        sel = [1, 3, 5, 7, 9, 11, 13, 15, 17, 18]
        w("| Lap completed | " + " | ".join("%d%%" % round(100 * pr["cps"][i]) for i in sel) + " |")
        w("|---|" + "---|" * len(sel))
        for m, lab in (("B0", "B0 no look at the lap"), ("M0", "M0 deviation stays"), ("M1", "M1 pace-scaled"), ("M2", "M2 fitted slope"),
                       ("M3", "M3 average of M1, M2")):
            w("| %s | " % lab + " | ".join("%.2f" % pr["rmse"][m][i] for i in sel) + " |")
        w("")
        w("Reading it: the prediction beats \"no look\" only once the error falls clearly below the B0 row. On simulated laps the best "
          "model was within about half a second from half-way, about a quarter of a second in the last quarter and a tenth only in "
          "the last 5 %; earlier it mostly tells you the lap is going well or badly.")
        w("")
        img("predictor")
        w("")
    elif not R.get("replay", True):
        w("The predictor replay was switched off (--no-replay).")
        w("")
    else:
        w("Not enough clean loops to replay the predictor (needs 4).")
        w("")
    w("## 6. How far to trust this")
    w("")
    dq = []                                         # what the per-file analysis found wrong with the recording itself
    for s_ in prep["sessions"]:
        try:
            with open(os.path.join(s_["analysis_dir"], "report.md"), encoding="utf-8") as fh_:
                for ln in fh_:
                    t = ln.strip()
                    if "WARNING" in t or t.startswith("- No standstill") or t.startswith("- The accelerometer reads") or t.startswith("- Speed fusion"):
                        dq.append((s_["name"], t.lstrip("- ").strip()))
        except OSError:
            pass
    if dq:
        w("**About this recording** (found by the per-file analysis; see its report.md):")
        w("")
        for nm_, t in dq:
            w("- %s: %s" % (nm_, t))
        w("")
    w("- Times come from a phone: GPS about 1 Hz with 2-3 m of slowly drifting error, plus motion sensors. Each section time is good to "
      "roughly +-0.03-0.06 s (more when the GPS scatter above is high); the ultimate lap's own error bar is printed above and is "
      "widened automatically when the GPS is noisy.")
    w("- The ultimate lap is the best you have *shown, in pieces*. Joining pieces assumes each can be repeated together; the speed "
      "correction handles the hand-over between corners to first order only.")
    w("- Brake and throttle are *inferred* from acceleration; there is no pedal sensor. Compare the same corner across laps, not different corners.")
    w("- The measurements, the stitching and the lap-time predictor were validated on simulated Sepang sessions with known answers "
      "(see the README). Their accuracy on real phone data is not yet established: expect to see different noise levels.")
    w("- Do not mix wet and dry runs, or very different tyre/fuel states, in one analysis unless you want them compared.")
    w("- Use the gaps to become more consistent first. Every piece of the ultimate lap is something you have already done, so "
      "the way to close a gap is repeating your best, not pushing past what you can do safely. Stay within your own limits, the "
      "car's and the organiser's rules, and never look at the phone while driving.")
    w("")
    if argv_note:
        w("_" + argv_note + "_")
    with open(os.path.join(out, "ultimate_report.md"), "w", encoding="utf-8") as f_:   # utf-8: a file name may not be plain ASCII
        f_.write("\n".join(L) + "\n")


def write(R, out, plots=True, argv_note=""):
    os.makedirs(out, exist_ok=True)
    S = summarise(R)
    files = {}
    write_csv(R, S, out)
    if plots:
        for key, fn, name in (("map", fig_map, "ultimate_map.png"), ("delta", fig_delta, "ultimate_delta.png"),
                              ("sections", fig_sections, "ultimate_sections.png"), ("inputs", fig_inputs, "ultimate_inputs.png"),
                              ("predictor", fig_predictor, "ultimate_predictor.png")):
            try:
                ok = fn(R, S, os.path.join(out, name))
                if ok is not False:
                    files[key] = name
            except Exception as e:                                      # a figure must never stop the report
                print("figure %s failed: %s" % (name, e))
    write_md(R, S, out, files, argv_note)
    return S, files
