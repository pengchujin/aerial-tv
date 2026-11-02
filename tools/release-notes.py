#!/usr/bin/env python3
"""Turn the latest GitHub releases into the notes the TV shows on its update page.

    gh api 'repos/pengchujin/aerial-tv/releases?per_page=12' | tools/release-notes.py > site/download/notes.json

Release notes are written two ways: a Chinese section and an English section, or each bullet in
Chinese with its English line indented under it. A section's heading says its language; under a
heading in both ("## 修复 / Fixes"), each line is sorted by the script it is written in. Either
way it comes out as {"v": "1.77", "zh": [...], "en": [...]}, newest first. Only bullets are kept
(the "how to update" paragraph is what the update page itself says), without Markdown, and
without Lite's lines, which the full app has no use for.
"""
import json
import re
import sys

KEEP = 12  # an owner coming from this many releases back still sees every change


def is_chinese(line: str) -> bool:
    # English lines name Chinese apps now and then ("小微畅聊 is listed"): count, don't test.
    han = len(re.findall(r"[㐀-鿿]", line))
    latin = len(re.findall(r"[A-Za-z]", line))
    return han * 2 > latin


def plain(text: str) -> str:
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = text.replace("**", "").replace("`", "")
    return re.sub(r"\s+", " ", text).strip()


def heading_lang(line: str):
    """"## 修复" or "## 中文" is a Chinese section, "## Fixes" an English one, "## 修复 / Fixes" both."""
    han = bool(re.search(r"[\u3400-\u9fff]", line))
    latin = bool(re.search(r"[A-Za-z]", line))
    return None if han == latin else "zh" if han else "en"


def bullets(body: str):
    """Each bullet as its section's language and its lines: the "- " line and the indented lines
    that go on from it."""
    item = None
    section = None
    for raw in body.splitlines():
        if raw.startswith("#"):
            if item:
                yield section, item
            item = None
            section = heading_lang(raw)
        elif re.match(r"^\s*[-*] ", raw):
            if item:
                yield section, item
            item = [re.sub(r"^\s*[-*] ", "", raw)]
        elif item is not None and raw.startswith((" ", "\t")) and raw.strip():
            item.append(raw.strip())
        else:
            if item:
                yield section, item
            item = None
    if item:
        yield section, item


def notes(body: str):
    out = {"zh": [], "en": []}
    for section, lines in bullets(body):
        # Consecutive lines in one language are one item (a wrapped bullet).
        parts = []
        for line in lines:
            lang = section or ("zh" if is_chinese(line) else "en")
            if parts and parts[-1][0] == lang:
                parts[-1][1].append(line)
            else:
                parts.append((lang, [line]))
        for lang, text in parts:
            text = plain(" ".join(text))
            if text and not text.startswith("Aerial Lite"):
                out[lang].append(text)
    return out


def main():
    releases = [r for r in json.load(sys.stdin) if not r.get("draft") and not r.get("prerelease")]
    result = []
    for r in releases[:KEEP]:
        n = notes(r.get("body") or "")
        if n["zh"] or n["en"]:
            result.append({"v": r["tag_name"].lstrip("v"), **n})
    json.dump({"releases": result}, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
