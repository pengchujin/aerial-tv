#!/usr/bin/env python3
"""Frame times of Aerial on a TV, per operation: presses remote keys over ADB, then reads
`dumpsys gfxinfo app.orchard.tv framestats`.

  python3 tools/perf.py <adb serial> [scenario ...]        e.g. 192.168.1.73:5555 row cc

Optional scenarios: resources (five-second process CPU and PSS sample, foreground recorded),
home-adb (send Home once), home-remote (observe while someone presses the physical Home key).
Home observations poll the resumed activity and can miss brief windows. They measure the
activity transition, not when the pixels were drawn. --json writes results to a local file.

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
import argparse
import json
import re, statistics, subprocess, time
from pathlib import Path

PKG = "app.orchard.tv"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial")
parser.add_argument("scenarios", nargs="*")
parser.add_argument("--json", type=Path, help="also save the measured results")
args = parser.parse_args()
S = args.serial
ONLY = args.scenarios


def sh(cmd, timeout=300):
    return subprocess.run(["adb", "-s", S, "shell", cmd], capture_output=True, text=True, timeout=timeout, check=True).stdout


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
    if r.get("frames") == 0:
        for key in ("p50", "p90", "p99"):
            r.pop(key, None)
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


def foreground():
    output = sh("dumpsys activity activities | grep -E 'topResumedActivity=|mResumedActivity:|mFocusedActivity:' || true")
    match = re.search(r" u\d+ ([\w.$]+/[^ }]+)", output)
    return match.group(1) if match else None


def cpu_sample():
    # USER_HZ is 100 on these Android ABIs. Keep the process start tick to detect restarts.
    output = sh(f'p=$(pidof {PKG}); [ -n "$p" ] && cat /proc/$p/stat /proc/uptime')
    stat, uptime = output.strip().splitlines()
    fields = stat.rsplit(")", 1)[1].split()
    return fields[19], int(fields[11]) + int(fields[12]), float(uptime.split()[0])


def resources():
    before = foreground()
    start, ticks, uptime = cpu_sample()
    time.sleep(5)
    end, final_ticks, final_uptime = cpu_sample()
    seconds = final_uptime - uptime
    output = sh(f"dumpsys meminfo {PKG}")
    pss = re.search(r"TOTAL(?: PSS)?:\s+(\d+)", output)
    return {"foreground_before": before, "foreground_after": foreground(),
            "sample_seconds": round(seconds, 2),
            "cpu_percent_one_core": round((final_ticks - ticks) / seconds, 2)
            if start == end and seconds > 0 else None,
            "pss_kib": int(pss[1]) if pss else None}


def home(name):
    # The first observed departure timestamps a manual transition. ADB runs are timed
    # from dispatch. Sampling can miss short intermediate windows; report what was seen.
    initial = foreground()
    if not initial or initial.startswith(PKG + "/"):
        parser.error("Start Home measurements from another app")
    changes = []
    started = time.monotonic()
    previous = initial
    if name == "home-adb":
        sh("input keyevent KEYCODE_HOME")
    else:
        print("Press Home once on the physical remote within 30 seconds.", flush=True)
    while time.monotonic() - started < 30:
        current = foreground()
        if current and current != previous:
            elapsed = round((time.monotonic() - started) * 1000)
            changes.append({"component": current, "observed_ms": elapsed})
            previous = current
            if current.startswith(PKG + "/"):
                break
        time.sleep(0.05)
    reached = previous.startswith(PKG + "/")
    return {"initial": initial, "changes": changes, "reached_aerial": reached,
            "adb_dispatch_to_observed_ms": changes[-1]["observed_ms"]
            if name == "home-adb" and reached else None}


unknown = set(ONLY) - set(SCENARIOS) - {"resources", "home-adb", "home-remote", "cold"}
if unknown:
    parser.error("Unknown scenarios: " + ", ".join(sorted(unknown)))

results = {}
for name in ONLY or list(SCENARIOS) + ["cold"]:
    if name == "resources":
        results[name] = resources()
        print(name, results[name], flush=True)
        continue
    if name in ("home-adb", "home-remote"):
        results[name] = home(name)
        print(name, results[name], flush=True)
        continue
    if name == "cold":
        results[name] = [cold() for _ in range(3)]
        print("cold", "start ms:", results[name], flush=True)
        continue
    keys([3, ("sleep", 2.5)])  # Home, out of any panel or screensaver
    if name.startswith("film_"):
        time.sleep(14)  # the film fades in after 10 s without keys
    sh(f"dumpsys gfxinfo {PKG} reset")
    SCENARIOS[name]()
    time.sleep(1)
    results[name] = collect()
    print(name, results[name], flush=True)
    keys([3, ("sleep", 2.5)])

if args.json:
    args.json.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
