"""Side-by-side showcase GIF: vanilla vs Chunk Express on the same ice-road ride, in real time.

Record the two rides first (frames are saved every 0.1 s of the ride, named <index>_<x>.png):

    ./gradlew runIceRoad -Plength=3000 -Pcapture=vanilla -Pwith=<create>,<farmersdelight>
    ./gradlew runIceRoadChunkExpress -Plength=3000 -Pcapture=chunkexpress -Poptimizations=on -Pwith=<same>

then:

    python art/make_showcase_gif.py [--from auto|<seconds>] [--seconds 12] [--width 400]

Both halves show the same moment of their ride (frame N of each = N x 0.1 s after setting off), so a
server stall is visible as it happens: the boat stops while the server waits for chunks, then jumps
ahead. A frame counts as stalled when the boat moved less than 2 blocks since the previous frame
(full speed is 7.2), and as slowed under 5. Each half shows its live speed, a red badge while it is
stalled and an amber one while slowed - all read from the recorded positions, nothing is staged.

With --from auto (the default) the clip is the window where vanilla stalls most compared with Chunk
Express. Output: art/showcase/vanilla-vs-chunkexpress.gif (git-ignored; for the Modrinth gallery).
"""
import argparse
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
CAPTURE = ROOT / "run" / "bench" / "chunkexpress-bench" / "capture"
OUT = Path(__file__).resolve().parent / "showcase"
FRAME_SECONDS = 0.1
STALL_BLOCKS = 2
# Full speed is 7.2 blocks per frame; under 5 (50 blocks/s) the slowdown is plainly visible.
SLOW_BLOCKS = 5


def frames(label):
    """[(x, path)] in capture order: index i is 0.1 s * i into the ride."""
    found = []
    for f in (CAPTURE / label).glob("*.png"):
        m = re.match(r"(\d+)_(-?\d+)\.png$", f.name)
        if m:
            found.append((int(m.group(1)), int(m.group(2)), f))
    if not found:
        raise SystemExit(f"no frames for '{label}' in {CAPTURE / label}")
    found.sort()
    return [(x, f) for _, x, f in found]


def stalled(seq, i):
    return i > 0 and seq[i][0] - seq[i - 1][0] < STALL_BLOCKS


def slowed(seq, i):
    return i > 0 and STALL_BLOCKS <= seq[i][0] - seq[i - 1][0] < SLOW_BLOCKS


def speed(seq, i):
    """Blocks per second over the last half second."""
    j = max(0, i - 5)
    return (seq[i][0] - seq[j][0]) / max(FRAME_SECONDS, (i - j) * FRAME_SECONDS)


def best_window(left, right, count):
    usable = min(len(left), len(right)) - count
    best, best_i = -1, 0
    for start in range(1, max(2, usable)):
        score = sum(stalled(left, i) for i in range(start, start + count)) - \
            sum(stalled(right, i) for i in range(start, start + count))
        if score > best:
            best, best_i = score, start
    return best_i, best


def font(size):
    for name in ("arialbd.ttf", "DejaVuSans-Bold.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--left", default="vanilla")
    ap.add_argument("--right", default="chunkexpress")
    ap.add_argument("--from", dest="start", default="auto", help="seconds into the ride where the clip starts, or auto")
    ap.add_argument("--seconds", type=float, default=12)
    ap.add_argument("--width", type=int, default=400, help="width of each half")
    ap.add_argument("--colors", type=int, default=128, help="GIF palette size (fewer = smaller file)")
    ap.add_argument("--out", default=None, help="file name in art/showcase (default <left>-vs-<right>.gif)")
    args = ap.parse_args()

    left, right = frames(args.left), frames(args.right)
    count = int(args.seconds / FRAME_SECONDS)
    if args.start == "auto":
        i0, score = best_window(left, right, count)
    else:
        i0, score = int(float(args.start) / FRAME_SECONDS), None
    count = min(count, len(left) - i0, len(right) - i0)
    stalls = [sum(stalled(seq, i) for i in range(i0, i0 + count)) for seq in (left, right)]
    slows = [sum(slowed(seq, i) for i in range(i0, i0 + count)) for seq in (left, right)]
    print(f"clip: {i0 * FRAME_SECONDS:.1f}-{(i0 + count) * FRAME_SECONDS:.1f} s into the ride; stalled frames: "
          f"{args.left} {stalls[0]}, {args.right} {stalls[1]}; slowed frames: {args.left} {slows[0]}, "
          f"{args.right} {slows[1]} (of {count})")

    sample = Image.open(left[0][1])
    w = args.width
    h = round(sample.height * w / sample.width)
    bar, foot, gap = 34, 24, 4
    title, small = font(20), font(14)
    out_frames = []
    for k in range(count):
        i = i0 + k
        canvas = Image.new("RGB", (2 * w + gap, h + bar + foot), (24, 24, 28))
        draw = ImageDraw.Draw(canvas)
        for col, (seq, name, colour) in enumerate(((left, "Vanilla", (255, 255, 255)),
                                                    (right, "Chunk Express", (126, 220, 96)))):
            ox = col * (w + gap)
            canvas.paste(Image.open(seq[i][1]).convert("RGB").resize((w, h), Image.LANCZOS), (ox, bar))
            draw.text((ox + 10, 6), name, fill=colour, font=title)
            readout = f"{speed(seq, i):.0f} blocks/s"
            draw.text((ox + w - 10 - draw.textlength(readout, font=small), 10), readout, fill=(200, 200, 206), font=small)
            badge = None
            if stalled(seq, i):
                badge, fill, ink = "waiting for chunks", (196, 40, 40), (255, 255, 255)
            elif slowed(seq, i):
                badge, fill, ink = "slowed down", (232, 168, 32), (24, 24, 28)
            if badge:
                bw = draw.textlength(badge, font=small) + 16
                draw.rectangle([ox + 8, bar + 8, ox + 8 + bw, bar + 30], fill=fill)
                draw.text((ox + 16, bar + 11), badge, fill=ink, font=small)
        label = f"real time, same seed - {i * FRAME_SECONDS:.1f} s into a full-speed ice-boat ride"
        draw.text(((2 * w + gap - draw.textlength(label, font=small)) / 2, h + bar + 4), label,
                  fill=(190, 190, 196), font=small)
        out_frames.append(canvas.quantize(colors=args.colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE))

    OUT.mkdir(exist_ok=True)
    target = OUT / (args.out or f"{args.left}-vs-{args.right}.gif")
    out_frames[0].save(target, save_all=True, append_images=out_frames[1:], duration=int(FRAME_SECONDS * 1000),
                       loop=0, optimize=True)
    size = target.stat().st_size / (1024 * 1024)
    print(f"wrote {target} ({len(out_frames)} frames, {2 * w + gap}x{h + bar + foot}, {size:.1f} MiB)")


if __name__ == "__main__":
    main()
