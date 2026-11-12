#!/usr/bin/env python3
"""Build site/icons for the website: the tile pictures Aerial can download per app.

    tools/icon-library.py <sources.json> <out dir>

sources.json (tools/icon-sources.json) lists where the pictures come from, best first:

    {"sources": [{"key": "aerial", "dir": "icons/aerial", "name": "Aerial", "url": "…",
                  "names": {"zh": "…"}}, …],
     "defaults": {"<package>": "<source key>[-<n>]"}}

In each source's dir, <package>.<png|jpg> is a picture of that app, and <package>@<n>.<ext>
another one (n = 2, 3, …). An app's pictures are listed in the order of the sources, the first
being the default unless "defaults" names another. Each picture is copied as
p/<package>-<hash>.<ext> (a changed picture gets a new name, so TVs fetch it again and caches can
keep the old one forever), and index.json says, for each app:

    {"v": 2,
     "icons":   {"<package>": "p/<package>-1a2b3c4d.png"},            the default (what Aerial 1.90 and older read)
     "choices": {"<package>": [{"id": "aerial", "p": "p/…", "by": "aerial"}, …]},
     "credits": {"aerial": {"name": "Aerial", "url": "…", "zh": "…"}, …}}

An id is the source key, with -<n> for a source's second and later pictures of the app; the
phone page keeps an owner's pick by it, so it stays the same when a picture is redrawn.
"""
import hashlib
import json
import os
import re
import shutil
import sys

PACKAGE = re.compile(r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$")
NAME = re.compile(r"^(?P<pkg>[^@]+?)(@(?P<n>[2-9]|[1-9][0-9]))?\.(?P<ext>png|jpg|jpeg)$", re.I)

manifest_path, out = sys.argv[1], sys.argv[2]
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
with open(manifest_path) as f:
    manifest = json.load(f)

shutil.rmtree(out, ignore_errors=True)
os.makedirs(os.path.join(out, "p"))
choices, credits, counts = {}, {}, {}
for source in manifest["sources"]:
    key = source["key"]
    src = os.path.join(root, source["dir"])
    if not os.path.isdir(src):
        sys.exit(f"error: {source['dir']} is missing; its pictures would be removed from the site")
    credit = {"name": source["name"]}
    if source.get("url"):
        credit["url"] = source["url"]
    credit.update(source.get("names", {}))
    credits[key] = credit
    found = []
    for name in os.listdir(src):
        m = NAME.match(name)
        if m and PACKAGE.match(m.group("pkg")):
            found.append((m.group("pkg"), int(m.group("n") or 1), name))
    for pkg, n, name in sorted(found):
        with open(os.path.join(src, name), "rb") as f:
            data = f.read()
        ext = os.path.splitext(name)[1].lower().replace(".jpeg", ".jpg")
        file = f"p/{pkg}-{hashlib.sha1(data).hexdigest()[:8]}{ext}"
        with open(os.path.join(out, file), "wb") as f:
            f.write(data)
        choices.setdefault(pkg, []).append({"id": key if n == 1 else f"{key}-{n}", "p": file, "by": key})
    counts[key] = len(found)

for pkg, want in manifest.get("defaults", {}).items():
    listed = choices.get(pkg, [])
    first = [c for c in listed if c["id"] == want]
    if not first:
        sys.exit(f"error: defaults names {want} for {pkg}, which has no such picture")
    choices[pkg] = first + [c for c in listed if c["id"] != want]

icons = {pkg: listed[0]["p"] for pkg, listed in choices.items()}
with open(os.path.join(out, "index.json"), "w") as f:
    json.dump({"v": 2, "icons": icons, "choices": choices, "credits": credits},
              f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
print(f"icon library: {len(icons)} apps, {sum(counts.values())} pictures ("
      + ", ".join(f"{k} {n}" for k, n in counts.items()) + ")")
