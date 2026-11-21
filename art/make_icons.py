"""Chunk Express icon options: vanilla-style 32x32 pixel art, exported at 512x512 (nearest neighbour).

Run:  python art/make_icons.py
Writes art/icon-options/<name>.png and art/icon-options/_overview.png (git-ignored drafts).
The chosen icon is option A, copied to art/icon.png (Modrinth, README) and
src/main/resources/chunkexpress.png (NeoForge mod list).
"""
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).parent / "icon-options"
SIZE = 32
SCALE = 16

GRASS = [(93, 156, 58), (79, 138, 47), (108, 171, 66), (71, 122, 41)]
DIRT = [(134, 96, 67), (108, 74, 48), (155, 118, 83), (91, 61, 38)]
STONE = [(125, 125, 125), (110, 110, 110), (143, 143, 143), (95, 95, 95)]
DEEPSLATE = [(77, 77, 82), (64, 64, 69), (90, 90, 96), (52, 52, 56)]
IRON = [(216, 216, 216), (168, 168, 168), (122, 122, 122), (86, 86, 86)]
GOLD = [(253, 245, 95), (233, 177, 21), (178, 100, 17), (122, 62, 10)]
PLANK = [(162, 130, 78), (141, 112, 66), (115, 91, 53)]
WHITE = (255, 255, 255)
OUTLINE = (32, 24, 18)
SKY = [(124, 168, 255), (110, 156, 245)]

FONT = {  # 5x7 blocky letters
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
}


def canvas(fill=(0, 0, 0, 0)):
    return Image.new("RGBA", (SIZE, SIZE), fill)


def noise_fill(img, box, palette, seed, weights=(6, 3, 2, 1)):
    rng = random.Random(seed)
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            img.putpixel((x, y), rng.choices(palette, weights=weights[:len(palette)])[0] + (255,))


def grass_side(img, box, seed=1):
    """Vanilla-like grass block side: dirt with a grass band and drips."""
    x0, y0, x1, y1 = box
    noise_fill(img, box, DIRT, seed)
    rng = random.Random(seed + 7)
    band = max(3, (y1 - y0) // 5)
    for x in range(x0, x1):
        depth = band + rng.choice([0, 0, 1, 1, 2, 3])
        for y in range(y0, min(y1, y0 + depth)):
            img.putpixel((x, y), rng.choices(GRASS, weights=(6, 3, 2, 1))[0] + (255,))


def letters(img, text, top_left, scale, color, outline=OUTLINE, shadow=True):
    x, y = top_left
    for ch in text:
        rows = FONT[ch]
        cells = [(cx, cy) for cy, row in enumerate(rows) for cx, c in enumerate(row) if c == "#"]
        pixels = set()
        for cx, cy in cells:
            for dx in range(scale):
                for dy in range(scale):
                    pixels.add((x + cx * scale + dx, y + cy * scale + dy))
        if outline:
            for px, py in pixels:
                for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1)):
                    q = (px + ox, py + oy)
                    if q not in pixels and 0 <= q[0] < SIZE and 0 <= q[1] < SIZE:
                        img.putpixel(q, outline + (255,))
        for px, py in pixels:
            c = color(px, py) if callable(color) else color
            img.putpixel((px, py), c + (255,))
        x += (len(rows[0]) * scale) + scale


def gold_shade(px, py):
    """Gold-block shading: light top-left, dark bottom-right."""
    t = (px + py) % 7
    return GOLD[0] if t < 2 else GOLD[1] if t < 5 else GOLD[2]


def border(img, color, width=1):
    for i in range(width):
        for x in range(SIZE):
            img.putpixel((x, i), color + (255,))
            img.putpixel((x, SIZE - 1 - i), color + (255,))
        for y in range(SIZE):
            img.putpixel((i, y), color + (255,))
            img.putpixel((SIZE - 1 - i, y), color + (255,))


def speed_lines(img, rows, x_from, x_to, color=WHITE):
    for y, length in rows:
        for x in range(x_from, min(x_to, x_from + length)):
            img.putpixel((x, y), color + (255,))


# ---- options -----------------------------------------------------------------------------

def option_grass_block():
    """A: a grass block side filling the icon, white CE on the dirt."""
    img = canvas()
    grass_side(img, (0, 0, SIZE, SIZE), seed=3)
    border(img, (60, 40, 25))
    letters(img, "CE", (5, 11), 2, WHITE)
    return img


def option_iso_block():
    """B: an inventory-style grass block with speed lines, CE on its front face."""
    img = canvas()
    # Isometric cube: top rhombus (2:1) and two side faces below its lower edges.
    cx, top = 18, 3
    w, h = 12, 6   # half-width and half-height of the top rhombus
    face = 13      # side face height
    rng = random.Random(11)
    for x in range(cx - w, cx + w + 1):
        # Lower edge of the rhombus at this column: lowest at the centre, rising to the corners.
        edge = top + h + ((w - abs(x - cx)) * h) // w
        upper = top + h - ((w - abs(x - cx)) * h) // w
        for y in range(upper, edge + 1):
            img.putpixel((x, y), rng.choices(GRASS, weights=(6, 3, 2, 1))[0] + (255,))
        left = x < cx
        for i in range(1, face + 1):
            y = edge + i
            if y >= SIZE:
                break
            band = i <= 2 + rng.choice([0, 0, 1])
            c = rng.choices(GRASS if band else DIRT, weights=(6, 3, 2, 1))[0]
            c = tuple(int(v * (0.70 if left else 0.88)) for v in c)
            img.putpixel((x, y), c + (255,))
    speed_lines(img, [(10, 5), (14, 7), (18, 4), (22, 6)], 0, 5)
    letters(img, "CE", (cx + 1, 18), 1, WHITE, outline=OUTLINE)
    return img


def option_minecart():
    """C: an express minecart carrying a grass block, on a rail, with speed lines."""
    img = canvas()
    # rail
    for x in range(1, 31):
        img.putpixel((x, 27), IRON[2] + (255,))
        img.putpixel((x, 28), IRON[3] + (255,))
    for x in range(2, 31, 5):
        for y in range(29, 31):
            for dx in range(3):
                img.putpixel((x + dx, y), PLANK[1] + (255,))
    # cargo: grass block
    grass_side(img, (11, 6, 25, 15), seed=5)
    # cart body
    for y in range(13, 24):
        for x in range(8, 28):
            shade = IRON[0] if y < 15 else IRON[1] if y < 21 else IRON[2]
            if x in (8, 27) or y in (13, 23):
                shade = IRON[3]
            img.putpixel((x, y), shade + (255,))
    # wheels
    for wx in (11, 24):
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                if dx * dx + dy * dy <= 5:
                    img.putpixel((wx + dx, 25 + dy), (40, 40, 44, 255))
    letters(img, "CE", (12, 15), 1, GOLD[1], outline=GOLD[3])
    speed_lines(img, [(14, 6), (17, 4), (20, 6)], 0, 7)
    return img


def option_gold_emblem():
    """D: gold CE letters on a deepslate tile."""
    img = canvas()
    noise_fill(img, (0, 0, SIZE, SIZE), DEEPSLATE, seed=9)
    border(img, (35, 35, 40), width=2)
    letters(img, "CE", (5, 9), 2, gold_shade, outline=GOLD[3])
    return img


def option_chunk_map():
    """E: a map-item style chunk grid with a lightning bolt."""
    img = canvas((0, 0, 0, 0))
    paper = (220, 200, 160)
    edge = (150, 120, 80)
    for y in range(2, 30):
        for x in range(2, 30):
            img.putpixel((x, y), paper + (255,))
    border_box = [(x, y) for x in range(2, 30) for y in (2, 29)] + [(x, y) for y in range(2, 30) for x in (2, 29)]
    for p in border_box:
        img.putpixel(p, edge + (255,))
    rng = random.Random(4)
    for gy in range(4):
        for gx in range(4):
            water = rng.random() < 0.25
            pal = [(64, 96, 220), (72, 108, 230)] if water else [(108, 160, 64), (96, 148, 56), (127, 178, 76)]
            for y in range(4 + gy * 6, 4 + gy * 6 + 5):
                for x in range(4 + gx * 6, 4 + gx * 6 + 5):
                    img.putpixel((x, y), rng.choice(pal) + (255,))
    # A fat zigzag bolt: dark outline drawn one pixel larger, then the gold fill.
    bolt = [(19, 4), (10, 17), (15, 17), (11, 27), (22, 13), (17, 13), (22, 4)]
    draw = ImageDraw.Draw(img)
    draw.polygon(bolt, fill=GOLD[3] + (255,), outline=GOLD[3] + (255,), width=2)
    draw.polygon(bolt, fill=GOLD[0] + (255,))
    for x, y in [(18, 6), (17, 8), (16, 10), (15, 12), (14, 14), (13, 22), (14, 20)]:
        img.putpixel((x, y), GOLD[1] + (255,))
    letters(img, "CE", (19, 21), 1, WHITE, outline=OUTLINE)
    return img


OPTIONS = [
    ("A-grass-block", "A  Grass block", option_grass_block),
    ("B-iso-block", "B  Block + speed", option_iso_block),
    ("C-express-cart", "C  Express cart", option_minecart),
    ("D-gold-emblem", "D  Gold emblem", option_gold_emblem),
    ("E-chunk-map", "E  Chunk map", option_chunk_map),
]


def main():
    OUT.mkdir(exist_ok=True)
    big = []
    for file, label, make in OPTIONS:
        img = make().resize((SIZE * SCALE, SIZE * SCALE), Image.NEAREST)
        img.save(OUT / f"{file}.png")
        big.append((label, img))
    # overview: each option at 256 px on a checkerboard, plus a 64 px preview (as on a mod list)
    cell = 256
    sheet = Image.new("RGBA", (len(big) * (cell + 24) + 24, cell + 120), (40, 40, 44, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arial.ttf", 22)
    except OSError:
        font = ImageFont.load_default()
    for i, (label, img) in enumerate(big):
        x = 24 + i * (cell + 24)
        checker = Image.new("RGBA", (cell, cell))
        for cy in range(0, cell, 16):
            for cx in range(0, cell, 16):
                c = (70, 70, 74, 255) if (cx // 16 + cy // 16) % 2 else (90, 90, 94, 255)
                ImageDraw.Draw(checker).rectangle([cx, cy, cx + 15, cy + 15], fill=c)
        checker.alpha_composite(img.resize((cell, cell), Image.NEAREST))
        sheet.alpha_composite(checker, (x, 24))
        small = img.resize((64, 64), Image.NEAREST)
        sheet.alpha_composite(small, (x, cell + 40))
        draw.text((x + 76, cell + 58), label, fill=(235, 235, 235, 255), font=font)
    sheet.save(OUT / "_overview.png")
    print("wrote", len(big), "options to", OUT)


if __name__ == "__main__":
    main()
