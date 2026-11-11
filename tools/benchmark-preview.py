#!/usr/bin/env python3
"""Repeatable release measurements; never changes HOME or stops the installed upstream app.

python3 tools/benchmark-preview.py --serial HOST:5555 --out local/bench/baseline
Run again after installing the candidate over the SAME preview package. Do not clear its data.
Raw captures can contain household information; keep them under ignored local/.
"""
import argparse
from collections import Counter
import csv
import io
import json
from pathlib import Path
import re
import statistics
import subprocess
import time

PACKAGE = 'app.orchard.tv.preview'
COMPONENT = PACKAGE + '/app.orchard.tv.MainActivity'


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100
    lo = int(position)
    hi = min(lo + 1, len(ordered) - 1)
    return round(ordered[lo] + (ordered[hi] - ordered[lo]) * (position - lo), 3)


def frames(text):
    """Parse named Android framestats columns; reject known special flags and unfinished fences."""
    header = None
    result = []
    for row in csv.reader(io.StringIO(text)):
        if row and row[0] == 'Flags':
            header = row
        elif header and row and row[0].isdigit() and len(row) == len(header):
            try:
                record = {k: int(v) for k, v in zip(header, row) if k}
                begin, end = record['IntendedVsync'], record['FrameCompleted']
                # Xiaomi firmware sets undocumented bit 16 on ordinary frames. Preserve
                # and report it; reject AOSP special bits rather than silently losing all data.
                if record['Flags'] & 0xF or begin <= 0 or end < begin or end >= 2**63 - 1:
                    continue
                gpu = record.get('GpuCompleted', 0) - record.get('SwapBuffers', 0)
                result.append({'flags': record['Flags'], 'frame_ms': (end-begin)/1e6,
                               'swap_to_gpu_ms': gpu/1e6 if record.get('GpuCompleted', 0) > 0 and record.get('SwapBuffers', 0) > 0 and 0 <= gpu < 10**10 else None})
            except (KeyError, ValueError):
                continue
    return result


def summarize(records):
    values = [x['frame_ms'] for x in records]
    gpu = [x['swap_to_gpu_ms'] for x in records if x['swap_to_gpu_ms'] is not None]
    return {'frames': len(values), 'flags': dict(Counter(x['flags'] for x in records)), 'frame_p50_ms': percentile(values, 50),
            'frame_p95_ms': percentile(values, 95), 'frame_p99_ms': percentile(values, 99),
            'max_ms': max(values, default=None),
            'over_16_67ms_percent': round(100*sum(x > 1000/60 for x in values)/len(values), 2) if values else None,
            'swap_to_gpu_p50_ms': percentile(gpu, 50), 'swap_to_gpu_p95_ms': percentile(gpu, 95)}


def platform_stats(text):
    """First/global counters, not the duplicated per-window section. Histogram bins are coarse."""
    def count(pattern):
        match = re.search(pattern, text)
        return int(match.group(1)) if match else None
    return {'frames': count(r'Total frames rendered: (\d+)'),
            'janky_frames': count(r'Janky frames: (\d+)'),
            'p50_ms': count(r'50th percentile: (\d+)ms'),
            'p95_ms': count(r'95th percentile: (\d+)ms'),
            'p99_ms': count(r'99th percentile: (\d+)ms'),
            'gpu_p95_ms': count(r'95th gpu percentile: (\d+)ms')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--launches', type=int, default=10)
    parser.add_argument('--rounds', type=int, default=5)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    def adb(*words, binary=False):
        return subprocess.check_output(['adb', '-s', args.serial, *words], timeout=90,
                                       text=not binary, stderr=subprocess.STDOUT)
    def save(name, text):
        (args.out/name).write_text(text)
    def shot(name):
        (args.out/name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    def key(code):
        adb('shell', 'input', 'keyevent', str(code))
    def focus():
        current = adb('shell', 'dumpsys', 'window')
        line = next((x for x in current.splitlines() if 'mCurrentFocus=' in x), '')
        if PACKAGE not in line:
            raise RuntimeError('Preview lost foreground; stopping: '+line)
    def start(cold=False):
        if cold:
            adb('shell', 'am', 'force-stop', PACKAGE)
        return adb('shell', 'am', 'start', '-W', '-n', COMPONENT)
    def gfx():
        return adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, 'framestats')
    save('device.txt', adb('shell', 'getprop'))
    save('package.txt', adb('shell', 'dumpsys', 'package', PACKAGE))
    save('compile.txt', adb('shell', 'cmd', 'package', 'compile', '-m', 'speed', '-f', PACKAGE))
    start(); time.sleep(8); focus(); shot('home.png')
    launches = []
    interactive = []
    for i in range(args.launches):
        output = start(cold=True)
        save(f'launch-{i}.txt', output)
        match = re.search(r'TotalTime: (\d+)', output)
        if not match or 'Status: ok' not in output:
            raise RuntimeError(output)
        launches.append(int(match.group(1)))
        time.sleep(3)
        focus()
        activity = adb('shell', 'dumpsys', 'activity', COMPONENT)
        save(f'launch-{i}-activity.txt', activity)
        marks = re.findall(r'Home interactive after content setup: (\d+) ms', activity)
        if marks:
            interactive.append(int(marks[-1]))
        save(f'launch-{i}-frames.txt', gfx())
        print(f'launch {i+1}/{args.launches}: {launches[-1]} ms', flush=True)
    result = {'cold_process_launch_ms': {'samples': launches, 'median': statistics.median(launches),
                                         'p95': percentile(launches, 95)}, 'scenarios': {}, 'interactive_after_setup_ms': {'samples': interactive, 'p50': percentile(interactive, 50), 'p95': percentile(interactive, 95)}}
    for scenario in ('navigation', 'panel'):
        all_frames = []
        platform_runs = []
        for i in range(args.rounds):
            start(cold=True); time.sleep(4); focus()
            shot(f'{scenario}-{i}-before.png')
            adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset')
            if scenario == 'navigation':
                for code in [22]*4+[21]*4+[20,22,21,19]+[21]*4:
                    key(code); time.sleep(.08)
            else:
                # From the top row, Up selects the status pill; Menu opens Control Center.
                # Menu avoids accidentally launching an app if the focus route regresses.
                key(19); time.sleep(.3); key(82); time.sleep(.7)
                for code in [22,22,21,21,20,19]:
                    key(code); time.sleep(.12)
            time.sleep(.8)
            focus()
            output = gfx(); save(f'{scenario}-{i}-frames.txt', output)
            shot(f'{scenario}-{i}-after.png')
            sample = frames(output); all_frames.extend(sample)
            platform_runs.append(platform_stats(output))
            print(scenario, i+1, summarize(sample), flush=True)
        result['scenarios'][scenario] = summarize(all_frames)
        result['scenarios'][scenario]['platform_runs'] = platform_runs
    save('memory.txt', adb('shell', 'dumpsys', 'meminfo', PACKAGE))
    save('crashes.txt', adb('logcat', '-d', '-b', 'crash', '-t', '200'))
    start(cold=True); time.sleep(5)
    adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset'); time.sleep(10)
    save('idle-frames.txt', gfx())
    result['idle'] = summarize(frames((args.out/'idle-frames.txt').read_text()))
    save('summary.json', json.dumps(result, indent=2))
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
