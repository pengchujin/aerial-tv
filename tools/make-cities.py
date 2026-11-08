#!/usr/bin/env python3
"""Builds app/src/main/assets/cities-cn.tsv, the places the phone page's city box finds by their
Chinese name. Open-Meteo's geocoder knows few Chinese names: 商丘 and 泉州 found nothing, and 安庆
found a village in Jiangsu instead of the city in Anhui (issue #187).

    curl -O https://download.geonames.org/export/dump/CN.zip && unzip CN.zip
    python3 -I tools/make-cities.py CN.txt

From GeoNames (CC BY 4.0, https://www.geonames.org): the capital, province and prefecture seats,
county seats, then county-level divisions whose name no seat has. One line per place:

    name  aliases(,)  province  lat  lon  rank  ascii

name is the simplified Chinese name without 市/县/区 (the app strips the same from what is typed):
the one GeoNames lists most often in some form (成都, 成都市), aliases the others (天府, 沪),
rank 0 for the capital and province seats, 1 prefecture, 2 county, 3 county-level division, ascii
GeoNames' romanized name (what ipinfo.io calls the city).
"""
import os
import re
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/cities-cn.tsv")
RANK = {"PPLC": 0, "PPLA": 0, "PPLA2": 1, "PPLA3": 2, "ADM3": 3}
# Prefecture cities GeoNames codes as a plain place (PPL), not as a seat: 锦州, 大连, 潍坊, 烟台,
# 扬州 and others were missing, so typing 锦州 found nothing (issue #1367). Above this population a
# plain place is a city of its own; below it are towns and Shanghai's districts (塘沽 535 000).
PPL_CITY_POPULATION = 600_000
# Places GeoNames still lists most often under a former name: 襄樊 became 襄阳 in 2010, and the
# weather showed the old name even after the viewer picked 襄阳 (issue #1328).
CURRENT = {"襄樊": "襄阳"}
CJK = re.compile(r"^[一-鿿]+$")
SUFFIX = re.compile(r"(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|自治县|地区|新区|省|市|县|区|盟|旗)$")


def simplified(name):
    # Traditional characters are outside GB2312; simplified place names are inside it.
    try:
        name.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False


def short(name):
    s = SUFFIX.sub("", name)
    return s if len(s) >= 2 else name


def chinese_names(alternates):
    names = [n for n in alternates.split(",") if CJK.match(n)]
    return [n for n in names if simplified(n)] or names


rows = []
provinces = {}
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        c = line.rstrip("\n").split("\t")
        code, admin1 = c[7], c[10]
        if code == "ADM1":
            # Not the one-character abbreviations (豫, 闽).
            names = [short(n) for n in chinese_names(c[3]) if len(short(n)) >= 2]
            if names:
                provinces[admin1] = min(names, key=len)
        elif code in RANK or (code == "PPL" and int(c[14] or 0) >= PPL_CITY_POPULATION):
            names = chinese_names(c[3])
            if names:
                rows.append((RANK.get(code, 1), -int(c[14] or 0), names, admin1, float(c[4]), float(c[5]), c[2]))

rows.sort(key=lambda r: (r[0], r[1]))
seen = set()
out = []
for rank, _, names, admin1, lat, lon, ascii_name in rows:
    forms = Counter(short(n) for n in names)
    name = max(forms, key=lambda n: (forms[n], len(n) >= 2, -len(n)))
    if CURRENT.get(name) in forms:
        name = CURRENT[name]
    elif name in CURRENT:
        forms[CURRENT[name]] += 1
        name = CURRENT[name]
    # A county-level division named like a seat already listed is that seat's area.
    if rank == 3 and (name, admin1) in seen:
        continue
    seen.add((name, admin1))
    aliases = ",".join(sorted(n for n in forms if n != name))
    out.append(f"{name}\t{aliases}\t{provinces.get(admin1, '')}\t{lat:.4f}\t{lon:.4f}\t{rank}\t{ascii_name}")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("# GeoNames (CC BY 4.0), tools/make-cities.py\n")
    f.write("\n".join(out) + "\n")
print(f"{len(out)} places, {os.path.getsize(OUT)} bytes -> {OUT}")
