#!/usr/bin/env python3
"""Generates the adaptive launcher icon as pixel-exact vector drawables.

Canvas is the 108x108dp adaptive-icon space at 1dp per pixel. Every same-colored run of pixels
merges into one path per color, so the output stays small and scales crisply at every density.

    python3 tools/gen_icon.py [--preview out.png]
"""
import argparse
from pathlib import Path

SIZE = 108
RES = Path(__file__).resolve().parent.parent / "app/src/main/res"

# --- Background: playfield well with a faint 6dp grid --------------------------------------------
BG = "#0E1230"
BG_GRID = "#1A2050"
BLOCK = 6  # dp per tetromino cell; the grid is aligned so column 9 starts at the icon center

# --- Tetromino palette: base, highlight, shadow ---------------------------------------------------
PIECES = {
    "I": ("#1EC8E8", "#9CF4FF", "#0B7A99"),
    "O": ("#F5D30F", "#FFF6A0", "#A88A00"),
    "T": ("#A23BD9", "#E0A6FF", "#5E1487"),
    "S": ("#3CCB3C", "#B4FFA8", "#16801E"),
    "Z": ("#E8312E", "#FFA59A", "#8E1010"),
    "L": ("#F58A1F", "#FFD29A", "#A04A00"),
    "J": ("#2F5BEA", "#A8BEFF", "#132C96"),
}

# Stack filling the full 18-column well from row 9 down, so parallax/pulse launcher animations that
# reveal the foreground beyond the 72dp viewport never expose an edge. The T-shaped slot at columns
# 8..10 waits for the falling T.
STACK_COL0, STACK_ROW0 = 0, 9
STACK = [
    "...ZZ.........L...",
    "I..JZZ..S.T.LLLS..",
    "IOOJJJOOSSTTOOISSZ",
    "IOOLZZOOISTJOOIJZZ",
    "ILLLTZZTIOOJJJIJZT",
    "ZZLLLTTTIOOZZSIJTT",
    "SZZIIIIOOJJJLLLTTT",
    "SSOOLLLIIIIJJJZZOO",
    "OSOOLJJJTTTZZIIIIO",
]
FALLING = [((8, 4), "T"), ((9, 4), "T"), ((10, 4), "T"), ((9, 5), "T")]  # (col, row) in block grid

# --- Logo: bold 4x5 arcade glyphs, 2dp per font pixel ---------------------------------------------
GLYPHS = {
    "T": ["####", ".##.", ".##.", ".##.", ".##."],
    "E": ["####", "##..", "###.", "##..", "####"],
    "R": ["###.", "##.#", "###.", "##.#", "##.#"],
    "I": ["##", "##", "##", "##", "##"],
    "S": ["####", "##..", "####", "..##", "####"],
}
TEXT = "TETRIS"
FONT_PX = 2
TEXT_TOP = 40
TEXT_ROWS = ["#FFF27A", "#FFD03A", "#FFA22A", "#FF6E26", "#EE3A2A"]  # top-to-bottom gradient
TEXT_OUTLINE = "#12081E"
TEXT_SHADOW = "#5A1030"
SHADOW_OFF = 2


def glyph_mask():
    """Returns {(x, y): row_index} of lit logo pixels in dp."""
    width = sum(len(GLYPHS[c][0]) for c in TEXT) + len(TEXT) - 1
    x0 = SIZE // 2 - width * FONT_PX // 2
    px = {}
    cx = x0
    for c in TEXT:
        g = GLYPHS[c]
        for gy, row in enumerate(g):
            for gx, ch in enumerate(row):
                if ch == "#":
                    for dy in range(FONT_PX):
                        for dx in range(FONT_PX):
                            px[(cx + gx * FONT_PX + dx, TEXT_TOP + gy * FONT_PX + dy)] = gy
        cx += (len(g[0]) + 1) * FONT_PX
    return px


def draw_block(img, col, row, piece):
    base, hi, lo = PIECES[piece]
    x0, y0 = col * BLOCK, row * BLOCK
    for y in range(BLOCK):
        for x in range(BLOCK):
            if x == BLOCK - 1 or y == BLOCK - 1:
                c = lo
            elif x == 0 or y == 0:
                c = hi
            else:
                c = base
            img[(x0 + x, y0 + y)] = c
    img[(x0 + 1, y0 + 1)] = "#FFFFFF"  # specular glint


def blocks():
    out = [((STACK_COL0 + i, STACK_ROW0 + j), p)
           for j, line in enumerate(STACK) for i, p in enumerate(line) if p != "."]
    return out + FALLING


def foreground():
    img = {}
    for (col, row), p in blocks():
        draw_block(img, col, row, p)
    text = glyph_mask()
    # Shadow, then 1dp outline around glyph + shadow, then glyph faces.
    shadow = {(x + SHADOW_OFF, y + SHADOW_OFF) for x, y in text}
    solid = set(text) | shadow
    for x, y in solid:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                img[(x + dx, y + dy)] = TEXT_OUTLINE
    for p in shadow:
        img[p] = TEXT_SHADOW
    for p, r in text.items():
        img[p] = TEXT_ROWS[r]
    return {p: c for p, c in img.items() if 0 <= p[0] < SIZE and 0 <= p[1] < SIZE}


def monochrome():
    """Alpha-only silhouette for themed icons: glyphs plus blocks inset by 1dp so they read apart."""
    img = {p: "#FFFFFF" for p in glyph_mask()}
    for (col, row), _ in blocks():
        for y in range(BLOCK - 1):
            for x in range(BLOCK - 1):
                img[(col * BLOCK + x, row * BLOCK + y)] = "#FFFFFF"
    return {p: c for p, c in img.items() if 0 <= p[0] < SIZE and 0 <= p[1] < SIZE}


def background():
    img = {(x, y): BG for x in range(SIZE) for y in range(SIZE)}
    for i in range(0, SIZE, BLOCK):
        for j in range(SIZE):
            img[(i, j)] = BG_GRID
            img[(j, i)] = BG_GRID
    return img


def to_vector(img):
    by_color = {}
    for y in range(SIZE):
        x = 0
        while x < SIZE:
            c = img.get((x, y))
            if c is None:
                x += 1
                continue
            end = x
            while end < SIZE and img.get((end, y)) == c:
                end += 1
            by_color.setdefault(c, []).append(f"M{x},{y}h{end - x}v1h{x - end}z")
            x = end
    paths = "\n".join(
        f'    <path\n        android:fillColor="{c}"\n        android:pathData="{"".join(runs)}" />'
        for c, runs in sorted(by_color.items())
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/gen_icon.py; edit the script, not this file. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{SIZE}dp"\n    android:height="{SIZE}dp"\n'
        f'    android:viewportWidth="{SIZE}"\n    android:viewportHeight="{SIZE}">\n'
        f"{paths}\n</vector>\n"
    )


ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


def write_preview(path, fg, bg, scale=8):
    from PIL import Image, ImageDraw

    def hexrgb(c):
        return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))

    def render(layers):
        im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        for layer in layers:
            for (x, y), c in layer.items():
                im.putpixel((x, y), hexrgb(c) + (255,))
        return im.resize((SIZE * scale, SIZE * scale), Image.NEAREST)

    full = render([bg, fg])
    # Circle mask of the 72dp viewport (radius 36dp), plus the 66dp safe zone ring for reference.
    mask = Image.new("L", full.size, 0)
    r = 36 * scale
    c = SIZE * scale // 2
    ImageDraw.Draw(mask).ellipse((c - r, c - r, c + r, c + r), fill=255)
    circle = Image.new("RGBA", full.size, (255, 255, 255, 255))
    circle.paste(full, mask=mask)
    circle = circle.crop((c - r, c - r, c + r, c + r))
    guide = full.copy()
    s = 33 * scale
    ImageDraw.Draw(guide).ellipse((c - s, c - s, c + s, c + s), outline=(255, 0, 255, 255), width=2)
    sheet = Image.new("RGBA", (guide.width + circle.width + 48 * 3 + 48, guide.height), (255, 255, 255, 255))
    sheet.paste(guide, (0, 0))
    sheet.paste(circle, (guide.width + 16, 0))
    small = circle.resize((48 * 3, 48 * 3), Image.LANCZOS)  # ~launcher size at xxhdpi
    sheet.paste(small, (guide.width + circle.width + 32, 0))
    sheet.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path)
    args = ap.parse_args()
    fg, bg, mono = foreground(), background(), monochrome()
    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    (drawable / "ic_launcher_foreground.xml").write_text(to_vector(fg))
    (drawable / "ic_launcher_background.xml").write_text(to_vector(bg))
    (drawable / "ic_launcher_monochrome.xml").write_text(to_vector(mono))
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(ADAPTIVE)
    if args.preview:
        write_preview(args.preview, fg, bg)


if __name__ == "__main__":
    main()
