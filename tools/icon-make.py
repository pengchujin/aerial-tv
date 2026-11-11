#!/usr/bin/env python3
"""Draw Aerial's own tile pictures for the icon library (the "aerial" source, see icon-library.py).

    python3 tools/icon-make.py tools/icons/aerial.json local/icons/aerial

Needs Pillow and rsvg-convert (brew install librsvg). Every app in the spec gets a 1280×720
JPEG in the style of the pictures le5inioo drew for #706: a diagonal gradient from [top] (upper
left) to [bottom] (lower right), a faint light in the upper left, and a white Material Symbols
Rounded glyph (Apache 2.0, tools/icons/glyphs) in the middle with a soft shadow under it.

    {"<package>": {"glyph": "settings", "colors": ["#8E8E93", "#48484A"], "size": 1.0}, …}

"size" scales the glyph (1.0: 40 % of the height, as Aerial's own system tiles).
"""
import json
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageChops, ImageDraw, ImageFilter

W, H = 1280, 720
HERE = os.path.dirname(os.path.abspath(__file__))


def rgb(hex_color):
    h = hex_color.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def gradient(top, bottom):
    """Diagonal: 0 in the upper left corner, 1 in the lower right."""
    t, b = rgb(top), rgb(bottom)
    down = Image.linear_gradient("L").resize((W, H), Image.BICUBIC)  # 0 at the top, 255 at the bottom
    across = Image.linear_gradient("L").rotate(90).resize((W, H), Image.BICUBIC)
    ramp = ImageChops.add(down, across, scale=2.0)
    out =Image.composite(Image.new("RGB", (W, H), b), Image.new("RGB", (W, H), t), ramp)
    # A faint light in the upper left, as on a lit glass panel.
    light = Image.new("L", (W, H), 0)
    ImageDraw.Draw(light).ellipse((-W * 0.35, -H * 0.9, W * 0.65, H * 0.7), fill=34)
    light = light.filter(ImageFilter.GaussianBlur(160))
    return Image.composite(Image.new("RGB", (W, H), (255, 255, 255)), out, light)


def glyph(name, box):
    """The glyph as a white RGBA image, [box] pixels on a side (the symbol's 960-unit box)."""
    with open(os.path.join(HERE, "icons", "glyphs", name + ".svg")) as f:
        svg = f.read().replace("<path ", '<path fill="#ffffff" ')
    with tempfile.TemporaryDirectory() as tmp:
        src, png = os.path.join(tmp, "g.svg"), os.path.join(tmp, "g.png")
        with open(src, "w") as f:
            f.write(svg)
        subprocess.run(["rsvg-convert", "-w", str(box), "-h", str(box), "-o", png, src], check=True)
        return Image.open(png).convert("RGBA")


def tile(spec):
    img = gradient(*spec["colors"]).convert("RGBA")
    box = round(H * 0.40 / 0.8 * spec.get("size", 1.0))  # Material glyphs fill about 80 % of their box
    g = glyph(spec["glyph"], box)
    x, y = (W - box) // 2, (H - box) // 2
    alpha = g.getchannel("A")
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 255), (x, y + 10), alpha)
    shadow.putalpha(ImageChops.multiply(shadow.getchannel("A"), Image.new("L", (W, H), 56)))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(22)))
    img.alpha_composite(g, (x, y))
    return img.convert("RGB")


spec_path, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
with open(spec_path) as f:
    specs = json.load(f)
for pkg, spec in specs.items():
    if pkg.startswith("//"):
        continue
    tile(spec).save(os.path.join(out, pkg + ".jpg"), quality=90, subsampling=0, optimize=True)
print(f"{out}: {len([p for p in specs if not p.startswith('//')])} pictures")
