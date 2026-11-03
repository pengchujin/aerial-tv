#!/usr/bin/env python3
"""Frame times of Aerial on a TV, per operation: presses remote keys over ADB, then reads
`dumpsys gfxinfo app.orchard.tv framestats`.

  python3 tools/perf.py <adb serial> [scenario ...]        e.g. 192.168.1.73:5555 row cc

Scenarios: row, row_hold, grid, cc, wallpaper, menu, idle, saver, cold (all when none given).
Per scenario it prints the system's own summary (frames, janky, p50/p90/p99, in whole ms, all
frames since the reset) and, from the last 120 frames' timestamps: med / q90 / max (vsync to frame
done), ui (vsync to the main thread handing over), rt (render thread) and gpu (GPU after swap;
Android 9+). A frame budget at 60 Hz is 16.7 ms; on a weak TV GPU the gpu column decides.

Before measuring: a release build, compiled (`cmd package compile -m speed -f app.orchard.tv`
after Aerial has started once), and Home in front. `wallpaper` reaches Personalize as Control
Center lays out on Xiaomi MiTV-MFTR0 (three rows down from Inputs: volume, brightness,
Personalize); check with a screenshot on other TVs. It only moves focus over the wallpaper cards,
never presses OK on them. Each `input keyevent` starts a Java process on the TV, which a 2-core
box feels; compare runs on the same TV only.
"""
import re, statistics, subprocess, sys, time

PKG = "app.orchard.tv"
S = sys.argv[1]
ONLY = sys.argv[2:]


def sh(cmd, timeout=300):
    return subprocess.run(["adb", "-s", S, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout


def keys(seq, gap=0.25):
    """Keycodes, 'burst:<code>x<n>' (one `input` call, as fast as it goes) or ('sleep', s), run in
    one shell on the TV so the pace does not depend on the computer's link."""
    parts = []
    for k in seq:
        if isinstance(k, tuple):
            parts.append(f"sleep {k[1]}")
            continue
        if str(k).startswith("burst:"):
            code, n = k[6:].split("x")
            parts.append("input keyevent " + " ".join([code] * int(n)))
        else:
            parts.append(f"input keyevent {k}")
        parts.append(f"sleep {gap}")
    sh("; ".join(parts))


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def collect():
    out = sh(f"dumpsys gfxinfo {PKG} framestats")
    r = {}
    for key, rx in [("frames", r"Total frames rendered: (\d+)"), ("janky", r"Janky frames: (\d+)"),
                    ("p50", r"50th percentile: (\d+)ms"), ("p90", r"90th percentile: (\d+)ms"),
                    ("p99", r"99th percentile: (\d+)ms")]:
        found = re.findall(rx, out)
        if found:
            r[key] = int(found[0])  # the first block is the whole app's
    total, ui, rt, gpu = [], [], [], []
    for block in re.findall(r"---PROFILEDATA---\n(.*?)---PROFILEDATA---", out, re.S):
        lines = [l for l in block.strip().splitlines() if l.strip()]
        if not lines:
            continue
        ix = {h: i for i, h in enumerate(lines[0].rstrip(",").split(","))}
        for line in lines[1:]:
            try:
                v = [int(x) for x in line.rstrip(",").split(",")]
            except ValueError:
                continue
            if v[ix["Flags"]] & 9:  # window changed, or a skipped frame
                continue
            ms = lambda a, b: (v[ix[b]] - v[ix[a]]) / 1e6
            total.append(ms("IntendedVsync", "FrameCompleted"))
            ui.append(ms("IntendedVsync", "SyncQueued"))
            rt.append(ms("SyncStart", "SwapBuffers"))
            if "GpuCompleted" in ix and v[ix["GpuCompleted"]] > 0:
                gpu.append(ms("SwapBuffers", "GpuCompleted"))
    if total:
        r.update(med=round(statistics.median(total), 1), q90=round(pct(total, 90), 1), max=round(max(total), 1),
                 ui=round(statistics.median(ui), 1), rt=round(statistics.median(rt), 1))
    if gpu:
        r.update(gpu=round(statistics.median(gpu), 1), gpu90=round(pct(gpu, 90), 1))
    return r


R, L, U, D, C, B, M = 22, 21, 19, 20, 23, 4, 82

SCENARIOS = {
    # Focus along the top row and back, at a remote's pace.
    "row": lambda: keys([R] * 6 + [L] * 6, gap=0.3),
    # A held key.
    "row_hold": lambda: keys(["burst:22x6", "burst:21x6"], gap=0.5),
    # Into the grid and back up (the page scrolls; a film stops and starts).
    "grid": lambda: keys([D, D, D, U, U, U], gap=0.6),
    # Control Center from the status pill: along its top buttons and down; never left or right on
    # the sliders, which would change the volume.
    "cc": lambda: keys([U, C, ("sleep", 1.5), R, R, L, L, D, D, D, U, U, U, B, ("sleep", 1)], gap=0.4),
    # Personalize, opened from Control Center: along the wallpaper cards, down and back, closed.
    "wallpaper": lambda: keys([U, C, ("sleep", 1.5), D, D, D, C, ("sleep", 2), R, R, R, L, L, L, D, D, U, U,
                               B, ("sleep", 1), B, ("sleep", 1)], gap=0.4),
    # The focused app's menu (Menu key), down and back, closed.
    "menu": lambda: keys([M, ("sleep", 1.2), D, D, U, U, B, ("sleep", 1)], gap=0.4),
    # Home left alone for 8 s: nothing should draw once a film has faded in.
    "idle": lambda: keys([("sleep", 8)]),
    # With a film wallpaper: the film fades in after 10 s on the top row and plays on, glass
    # samples swapping under the plate (needs Home in front and the film chosen; `film_*` reset
    # the counters only once the film has had time to start).
    "film_idle": lambda: keys([("sleep", 20)]),
    # The first keys on a playing film: it stops, its frame is fetched, focus moves.
    "film_row": lambda: keys([R] * 6 + [L] * 6, gap=0.3),
    # The screensaver coming in, 8 s of it, and a key back.
    "saver": lambda: (sh(f"am start -a {PKG}.PREVIEW_SCREENSAVER -n {PKG}/.MainActivity"), keys([("sleep", 8), D, ("sleep", 2)])),
}


def cold():
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    m = re.search(r"TotalTime: (\d+)", sh(f"am start -W -n {PKG}/.MainActivity"))
    time.sleep(4)
    return int(m.group(1)) if m else None


for name in ONLY or list(SCENARIOS) + ["cold"]:
    if name == "cold":
        print("cold", "start ms:", [cold() for _ in range(3)], flush=True)
        continue
    keys([3, ("sleep", 2.5)])  # Home, out of any panel or screensaver
    if name.startswith("film_"):
        time.sleep(14)  # the film fades in after 10 s without keys
    sh(f"dumpsys gfxinfo {PKG} reset")
    SCENARIOS[name]()
    time.sleep(1)
    print(name, collect(), flush=True)
    keys([3, ("sleep", 2.5)])
