"""Point-mass quasi-steady-state model of a Porsche 991.2 GT3.

Published / measured anchors (see the write-up for sources):
  curb 1,505 kg (Car and Driver), 368 kW @ 8,250 rpm, wheelbase 2,457 mm,
  ~154-159 kg downforce at ~300-318 km/h, Cd ~0.33, skidpad 1.11 g,
  70-0 mph in 146 ft, 0-60 3.0 s, 0-100 mph 6.9 s, 0-150 mph 16.3 s,
  1/4 mile 11.1 s @ 127 mph, top speed 318 km/h.
Assumed (not published): weight distribution, CG height, aero balance, grip.
"""
from dataclasses import dataclass, replace
import numpy as np

G = 9.81
MPH = 0.44704
KMH = 1 / 3.6


@dataclass(frozen=True)
class Car:
    m: float = 1580.0       # kg: 1,505 curb + driver, part-fuelled
    wf: float = 0.38        # static front weight fraction (assumed)
    L: float = 2.457        # wheelbase [m]
    h: float = 0.47         # CG height [m] (assumed)
    CdA: float = 0.73       # m^2
    ClA: float = 0.35       # m^2 (downforce area)
    aero_rear: float = 0.60 # share of downforce on rear axle (assumed)
    mu_x: float = 1.20      # longitudinal grip coefficient (track-warm Cup 2)
    mu_y: float = 1.20      # lateral grip coefficient
    crr: float = 0.013
    P: float = 368e3        # crank power [W]
    eta: float = 0.88       # driveline efficiency incl. shift losses (fitted)
    rho: float = 1.20

    @property
    def wr(self):
        return 1.0 - self.wf

    def q(self, v):
        return 0.5 * self.rho * v * v

    def drag(self, v):
        return self.q(v) * self.CdA

    def dforce(self, v):
        return self.q(v) * self.ClA

    def roll(self):
        return self.crr * self.m * G


# ---------------------------------------------------------------- limits
def a_power(car, v):
    """Longitudinal accel if power-limited (no lateral)."""
    Fp = car.eta * car.P / max(v, 1.0)
    return (Fp - car.drag(v) - car.roll()) / car.m


def a_traction(car, v, ay=0.0):
    """Max longitudinal accel the REAR axle can transmit, with lateral demand ay.
    Rear friction circle: (Fx/(mu N_r))^2 + (Fy_r/(mu N_r))^2 <= 1,
    N_r = m g wr + m a h/L + rear downforce ;  Fy_r = m ay wr ;  Fx = m a + drag + roll."""
    Fd, Fr = car.drag(v), car.roll()
    Fdn_r = car.aero_rear * car.dforce(v)
    # lateral utilisation of the whole car (assumed balanced, so the rear axle uses the same fraction)
    xi = min(1.0, ay / ay_max(car, v))
    k = car.mu_x * np.sqrt(1.0 - xi * xi)
    return (k * (car.m * G * car.wr + Fdn_r) - Fd - Fr) / (car.m * (1.0 - k * car.h / car.L))


def a_traction_straight(car, v):
    """Closed form of a_traction for ay = 0:
    m a + Fd + Fr = mu (m g wr + m a h/L + Fdn_r)."""
    Fd, Fr = car.drag(v), car.roll()
    Fdn_r = car.aero_rear * car.dforce(v)
    return (car.mu_x * (car.m * G * car.wr + Fdn_r) - Fd - Fr) / (car.m * (1.0 - car.mu_x * car.h / car.L))


def a_accel(car, v, ay=0.0):
    ap = a_power(car, v)
    at = a_traction_straight(car, v) if ay == 0.0 else a_traction(car, v, ay)
    return min(ap, at), ("power" if ap <= at else "traction")


def a_brake(car, v, ay=0.0):
    """Max deceleration (positive number), ideal brake bias, 4-wheel friction circle."""
    N = car.m * G + car.dforce(v)
    lat = car.m * ay / (car.mu_y * N)
    if lat >= 1.0:
        return 0.0
    Fb = car.mu_x * N * np.sqrt(1.0 - lat ** 2)
    return (Fb + car.drag(v) + car.roll()) / car.m


def ay_max(car, v):
    return car.mu_y * (car.m * G + car.dforce(v)) / car.m


def v_corner(car, R):
    """Steady-state grip-limited speed on radius R, aero included."""
    k0 = car.mu_y * 0.5 * car.rho * car.ClA / car.m
    kap = 1.0 / R
    if kap <= k0:
        return np.inf
    return np.sqrt(car.mu_y * G / (kap - k0))


def r_star(car):
    """Radius above which downforce grip scales faster than the demand: no grip limit."""
    return 2 * car.m / (car.mu_y * car.rho * car.ClA)


# ---------------------------------------------------------------- standing start
def standing_start(car, v_end=100.0, dt=0.002, v0=1.0):
    t, v, s = 0.0, v0, 0.0
    rows = [(t, v, s)]
    while v < v_end and t < 60:
        a, _ = a_accel(car, v)
        v += a * dt
        s += v * dt
        t += dt
        rows.append((t, v, s))
    return np.array(rows)


def time_to(rows, vtarget):
    i = np.searchsorted(rows[:, 1], vtarget)
    return np.interp(vtarget, rows[i - 1:i + 1, 1], rows[i - 1:i + 1, 0])


def dist_to_time(rows, ttarget):
    return np.interp(ttarget, rows[:, 0], rows[:, 2]), np.interp(ttarget, rows[:, 0], rows[:, 1])


def top_speed(car):
    lo, hi = 50.0, 150.0
    for _ in range(60):
        mid = 0.5 * (lo + hi)
        if a_power(car, mid) > 0:
            lo = mid
        else:
            hi = mid
    return lo
