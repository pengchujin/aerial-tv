#!/usr/bin/env python3
"""Build site/icons for the website: the tile pictures Aerial can download per app.

    tools/icon-library.py <source dir> <out dir>

Every <package>.<png|jpg> in the source dir is copied as p/<package>-<hash>.<ext> (a changed
picture gets a new name, so TVs fetch it again and caches can keep the old one forever), and
index.json maps each package to its file:  {"v": 1, "icons": {"com.ktcp.video": "p/com.ktcp.video-1a2b3c4d.jpg"}}
"""
import hashlib
import json
import os
import re
import shutil
import sys

PACKAGE = re.compile(r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$")

src, out = sys.argv[1], sys.argv[2]
shutil.rmtree(out, ignore_errors=True)
os.makedirs(os.path.join(out, "p"))
icons = {}
for name in sorted(os.listdir(src)):
    pkg, ext = os.path.splitext(name)
    if ext.lower() not in (".png", ".jpg", ".jpeg") or not PACKAGE.match(pkg):
        continue
    with open(os.path.join(src, name), "rb") as f:
        data = f.read()
    file = f"p/{pkg}-{hashlib.sha1(data).hexdigest()[:8]}{ext.lower()}"
    with open(os.path.join(out, file), "wb") as f:
        f.write(data)
    icons[pkg] = file
with open(os.path.join(out, "index.json"), "w") as f:
    json.dump({"v": 1, "icons": icons}, f, separators=(",", ":"), sort_keys=True)
print(f"icon library: {len(icons)} pictures")
