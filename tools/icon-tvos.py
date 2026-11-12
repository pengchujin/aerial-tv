#!/usr/bin/env python3
"""Fetch the apps' own tvOS (Apple TV) App Store icons into an icon library source dir.

    python3 tools/icon-tvos.py tools/icons/tvos.tsv local/icons/tvos

Each line of the list is "<storefront>\t<App Store id>\t<package> [<package> …]". The icon is the
app's Apple TV one (1280×768, flattened from its layers by Apple's image server), cut to 16:9
around its middle and saved as <package>.png for each package. Needs Pillow.

The App Store's web page reads its catalog through https://apps.apple.com/api/apps/…, which answers
without a token when the request looks like the page's own: the "x-apple-client-version" below is
the `qa=` constant in https://apps.apple.com/assets/index~*.js (2026-11-10), and changes when Apple
ships a new site. It answers 429 now and then; the script waits and tries again. The icon is
data[0].attributes.platformAttributes.appletvos.artwork, present when deviceFamilies has "tvos".
"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request

from PIL import Image

CLIENT_VERSION = "2642.2.0-external"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130 Safari/537.36")


def get(url, headers, tries=6):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, **headers})
            return urllib.request.urlopen(req, timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or i == tries - 1:
                raise
            time.sleep(15 + 5 * i)


def tvos_icon(storefront, app_id):
    lang = "zh-Hans-CN" if storefront == "cn" else "en-US"
    url = (f"https://apps.apple.com/api/apps/v1/catalog/{storefront}/apps/{app_id}"
           f"?platform=web&additionalPlatforms=appletv%2Cipad%2Ciphone&l={lang}")
    data = json.loads(get(url, {
        "x-apple-client-version": CLIENT_VERSION,
        "Accept": "application/json",
        "Origin": "https://apps.apple.com",
        "Referer": f"https://apps.apple.com/{storefront}/app/id{app_id}?platform=appletv",
    }))
    attributes = data["data"][0]["attributes"]
    artwork = attributes.get("platformAttributes", {}).get("appletvos", {}).get("artwork")
    if not artwork:
        return None
    template = artwork["url"]
    w, h = artwork.get("width", 1280), artwork.get("height", 768)
    return get(template.replace("{w}", str(w)).replace("{h}", str(h)).replace("{c}", "bb").replace("{f}", "png"), {})


listing, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
for name in os.listdir(out):
    os.remove(os.path.join(out, name))
count = 0
for line in open(listing, encoding="utf-8"):
    if not line.strip() or line.startswith("#"):
        continue
    storefront, app_id, packages = line.rstrip("\n").split("\t")
    png = tvos_icon(storefront, app_id)
    if png is None:
        print(f"{storefront} {app_id}: no tvOS version", file=sys.stderr)
        continue
    img = Image.open(io.BytesIO(png)).convert("RGB")
    w, h = img.size
    th = round(w * 9 / 16)
    if th < h:
        img = img.crop((0, (h - th) // 2, w, (h - th) // 2 + th))
    for pkg in packages.split():
        img.save(os.path.join(out, pkg + ".png"), optimize=True)
        count += 1
    time.sleep(2)
print(f"{out}: {count} pictures")
