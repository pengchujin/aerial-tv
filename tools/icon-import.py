#!/usr/bin/env python3
"""Fetch contributed tile pictures into an icon library source dir (see tools/icon-library.py).

    tools/icon-import.py tools/icons/le5inioo.tsv local/icons/le5inioo

Each line of the list is "<url>\t<package> [<package> …]\t<name>" ("-" for a picture not used yet; the
<name> is only for people). The picture is saved as <package>.png for each package, or
<package>@2.png, @3 … when the package already has one from an earlier line, so the first line for
an app gives its default.
"""
import os
import sys
import urllib.request

listing, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
for name in os.listdir(out):
    os.remove(os.path.join(out, name))
seen = {}
for line in open(listing, encoding="utf-8"):
    if not line.strip() or line.startswith("#"):
        continue
    url, packages = line.rstrip("\n").split("\t")[:2]
    if packages.strip() == "-":
        continue
    req = urllib.request.Request(url, headers={"User-Agent": "aerial-icon-import"})
    data = urllib.request.urlopen(req, timeout=30).read()
    ext = ".jpg" if data[:3] == b"\xff\xd8\xff" else ".png"
    for pkg in packages.split():
        n = seen[pkg] = seen.get(pkg, 0) + 1
        with open(os.path.join(out, pkg + ("" if n == 1 else f"@{n}") + ext), "wb") as f:
            f.write(data)
print(f"{out}: {sum(seen.values())} pictures of {len(seen)} apps")
