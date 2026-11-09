#!/usr/bin/env python3
"""Pick one landscape photo per day and publish a Bing-compatible daily.json.

The Aerial app reads /daily.json, /health and /img/*.jpg from the companion nginx container.
All paths and limits come from environment variables so the same image works for any folder.
"""
import datetime
import json
import os
import random
import struct
import time

from PIL import Image, ImageOps

PHOTOS = os.environ.get("PHOTOS_DIR", "/photos")
DATA = os.environ.get("DATA_DIR", "/data")
STATE = os.environ.get("STATE_DIR", "/state")
DAYS = max(1, int(os.environ.get("DAYS", "8")))
GEN_HOUR = max(0, min(23, int(os.environ.get("GEN_HOUR", "4"))))
UHD = os.environ.get("UHD", "1").lower() not in ("0", "false", "no")
HISTORY_DAYS = max(1, int(os.environ.get("HISTORY_DAYS", "30")))
TITLE = os.environ.get("ALBUM_TITLE", "Local album")
COPYRIGHT = os.environ.get("ALBUM_COPYRIGHT", "From local album")
W, H = 1920, 1080
UHD_W, UHD_H = 3840, 2160
IMG_DIR = os.path.join(DATA, "img")
DAILY_JSON = os.path.join(DATA, "daily.json")
HISTORY = os.path.join(STATE, "history.json")


def log(message):
    print("[%s] %s" % (datetime.datetime.now().strftime("%F %T"), message), flush=True)


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def atomic_write(path, data):
    tmp = path + ".tmp"
    if isinstance(data, bytes):
        with open(tmp, "wb") as f:
            f.write(data)
    else:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(data)
    os.chmod(tmp, 0o644)
    os.replace(tmp, path)


def load_history():
    try:
        with open(HISTORY, encoding="utf-8") as f:
            value = json.load(f)
        if isinstance(value, dict) and isinstance(value.get("used"), dict):
            return value
    except Exception:
        pass
    return {"used": {}}


def save_history(history):
    os.makedirs(STATE, exist_ok=True)
    atomic_write(HISTORY, json.dumps(history, ensure_ascii=False, indent=1))


def jpeg_size(path):
    """Read the SOF header only: opening thousands of full images would be slow."""
    try:
        with open(path, "rb") as f:
            data = f.read(262144)
        if data[:2] != b"\xff\xd8":
            return None
        i = 2
        while i < len(data) - 9:
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            i += 2
            if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
                continue
            length = struct.unpack(">H", data[i:i + 2])[0]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                          0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                height, width = struct.unpack(">HH", data[i + 3:i + 7])
                return width, height
            i += length
    except Exception:
        return None
    return None


def all_landscapes():
    result = []
    for root, _, files in os.walk(PHOTOS):
        for name in files:
            if not name.lower().endswith((".jpg", ".jpeg")):
                continue
            path = os.path.join(root, name)
            size = jpeg_size(path)
            if size and size[0] > size[1] and size[0] >= W:
                result.append(path)
    result.sort()
    return result


def candidates(history):
    used = history.get("used", {})
    today = datetime.date.today()
    result = []
    for path in all_landscapes():
        date = used.get(path)
        if date:
            try:
                if (today - datetime.date.fromisoformat(date)).days < HISTORY_DAYS:
                    continue
            except Exception:
                pass
        result.append(path)
    return result


def pick_photo(history):
    pool = candidates(history)
    if not pool:
        log("WARN no unused landscape photo; allowing reuse")
        pool = all_landscapes()
    if not pool:
        return None
    return random.choice(pool)


def image_path(date, uhd=False):
    suffix = "_UHD.jpg" if uhd else ".jpg"
    return os.path.join(IMG_DIR, date.strftime("%Y%m%d") + suffix)


def make_image(src, dst, size):
    image = Image.open(src)
    image = ImageOps.exif_transpose(image)
    image = ImageOps.fit(image, size, method=Image.LANCZOS)
    image = image.convert("RGB")
    tmp = dst + ".tmp"
    image.save(tmp, "JPEG", quality=88, optimize=True, progressive=True)
    os.chmod(tmp, 0o644)
    os.replace(tmp, dst)


def write_daily_json():
    today = datetime.date.today()
    entries = []
    for offset in range(DAYS):
        date = today - datetime.timedelta(days=offset)
        path = image_path(date)
        if not os.path.exists(path):
            continue
        if UHD:
            uhd = image_path(date, True)
            if not os.path.exists(uhd):
                atomic_write(uhd, read_bytes(path))
        entries.append({
            "startdate": date.strftime("%Y%m%d"),
            "url": "/img/%s.jpg" % date.strftime("%Y%m%d"),
            "urlbase": "/img/%s" % date.strftime("%Y%m%d"),
            "title": TITLE,
            "copyright": "%s · %s" % (COPYRIGHT, date.strftime("%Y-%m-%d")),
        })
    atomic_write(DAILY_JSON, json.dumps({"images": entries}, ensure_ascii=False))
    keep = set()
    for offset in range(DAYS):
        date = today - datetime.timedelta(days=offset)
        keep.add(date.strftime("%Y%m%d") + ".jpg")
        if UHD:
            keep.add(date.strftime("%Y%m%d") + "_UHD.jpg")
    try:
        for name in os.listdir(IMG_DIR):
            if name.endswith(".jpg") and name not in keep:
                os.remove(os.path.join(IMG_DIR, name))
    except FileNotFoundError:
        pass
    log("daily.json updated, %d entries" % len(entries))
    return entries


def ensure_today(history):
    os.makedirs(IMG_DIR, exist_ok=True)
    today = datetime.date.today()
    base = image_path(today)
    if not os.path.exists(base):
        src = pick_photo(history)
        if src:
            log("picking %s" % src)
            make_image(src, base, (W, H))
            if UHD:
                if jpeg_size(src) and jpeg_size(src)[0] >= UHD_W and jpeg_size(src)[1] >= UHD_H:
                    make_image(src, image_path(today, True), (UHD_W, UHD_H))
                else:
                    atomic_write(image_path(today, True), read_bytes(base))
            history.setdefault("used", {})[src] = today.isoformat()
            save_history(history)
        else:
            log("ERROR no photo available")
    write_daily_json()


def backfill(history):
    os.makedirs(IMG_DIR, exist_ok=True)
    today = datetime.date.today()
    for offset in range(DAYS):
        date = today - datetime.timedelta(days=offset)
        if os.path.exists(image_path(date)):
            continue
        src = pick_photo(history)
        if not src:
            break
        log("backfill %s <- %s" % (date, src))
        make_image(src, image_path(date), (W, H))
        if UHD:
            if jpeg_size(src) and jpeg_size(src)[0] >= UHD_W and jpeg_size(src)[1] >= UHD_H:
                make_image(src, image_path(date, True), (UHD_W, UHD_H))
            else:
                atomic_write(image_path(date, True), read_bytes(image_path(date)))
        history.setdefault("used", {})[src] = date.isoformat()
    save_history(history)
    write_daily_json()


def main():
    os.makedirs(IMG_DIR, exist_ok=True)
    os.makedirs(STATE, exist_ok=True)
    if not os.path.isdir(PHOTOS):
        log("ERROR PHOTOS_DIR does not exist: %s" % PHOTOS)
    history = load_history()
    if not os.path.exists(DAILY_JSON) or not os.path.exists(image_path(datetime.date.today())):
        backfill(history)
    else:
        write_daily_json()
    while True:
        now = datetime.datetime.now()
        next_run = now.replace(hour=GEN_HOUR, minute=0, second=0, microsecond=0)
        if next_run <= now:
            next_run += datetime.timedelta(days=1)
        wait = (next_run - now).total_seconds()
        log("sleeping %.0fs until %s" % (wait, next_run.strftime("%F %T")))
        time.sleep(wait)
        try:
            ensure_today(load_history())
        except Exception as error:
            log("ERROR %r" % error)


if __name__ == "__main__":
    main()
