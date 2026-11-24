#!/usr/bin/env python3
"""Review optional OEM services from a Mac. Scan and preview only read the TV.
Apply requires the exact reviewed package name. Local records are bound to hardware
serial and Android user. Restore uses the original PackageManager enabled setting.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

# (manufacturer device, product). Require an exact match; shared device codes exist.
CATALOG = {
    ("pig_chivalrous", "Product_bellehighlight"): {
        "com.xgimi.bugreportsender": "Vendor bug-report sender. Disabling can prevent support uploads."},
    ("T966D5", "T966D5_4K_CN"): {
        "com.tcl.bootadservice": "Vendor boot advertising service. Boot behaviour after disabling is unverified."},
}
MODES = {0: "default-state", 1: "enable", 2: "disable", 3: "disable-user", 4: "disable-until-used"}


def adb(serial, *args):
    return subprocess.check_output(["adb", "-s", serial, "shell", *args], text=True,
                                   timeout=20).strip()


def enabled(shell, user, package):
    output = shell("dumpsys", "package", package)
    line = re.search(rf"^\s*User {user}: [^\n]*\binstalled=true\b[^\n]*\benabled=(\d+)\b",
                     output, re.M)
    if not line or int(line[1]) not in MODES:
        raise ValueError(f"{package}: unavailable for user {user}")
    return int(line[1])


def save(path, record):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(record, indent=2) + "\n")
    os.replace(temp, path)


def run(shell, root, action, package):
    user = shell("am", "get-current-user")
    serial = shell("getprop", "ro.serialno")
    if not user.isdecimal() or serial.lower() in {"", "unknown"}:
        raise ValueError("Could not identify hardware and active Android user")
    target = tuple(shell("getprop", key) for key in ("ro.product.device", "ro.product.name"))
    firmware = shell("getprop", "ro.build.fingerprint")
    catalog = CATALOG.get(target, {})
    key = hashlib.sha256(serial.encode()).hexdigest()
    root.mkdir(mode=0o700, exist_ok=True, parents=True)
    path = root / f"{key}-{user}.json"
    with path.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        records = json.loads(path.read_text()) if path.exists() else {}
        if action in {"scan", "preview", "check"}:
            installed = set(shell("pm", "list", "packages", "--user", user).splitlines())
            for name in sorted(set(records) | set(catalog)):
                state = enabled(shell, user, name) if "package:" + name in installed else None
                print(json.dumps(dict(package=name, reason=catalog.get(name), current=state,
                                      recorded=records.get(name), firmware=firmware,
                                      would_disable=state in (0, 1) and name in catalog)))
            return
        if not package or not re.fullmatch(r"[a-zA-Z][\w]*(?:\.[\w]+)+", package):
            raise ValueError("Specify one package from the preview")
        if action == "apply" and package not in catalog:
            raise ValueError("No matching reviewed entry for this device")
        if action == "restore" and package not in records:
            raise ValueError("No saved state for this package")
        current = enabled(shell, user, package)
        if action == "apply":
            if package not in records and current not in (0, 1):
                print("Already disabled; no change")
                return
            desired = 3
            if package not in records:
                records[package] = dict(original=current, firmware=firmware)
                save(path, records)
        else:
            desired = records[package]["original"]
        if current != desired:
            shell("pm", MODES[desired], "--user", user, package)
        if enabled(shell, user, package) != desired:
            raise ValueError("Read-back failed; the recovery record was retained")
        if action == "restore":
            del records[package]
            save(path, records)
        print(f"{package}: verified enabled={desired}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("action", choices=("scan", "preview", "apply", "restore", "check"), default="preview", nargs="?")
    parser.add_argument("package", nargs="?")
    args = parser.parse_args()
    run(lambda *cmd: adb(args.serial, *cmd), Path.home() / ".aerial" / "package-state",
        args.action, args.package)
