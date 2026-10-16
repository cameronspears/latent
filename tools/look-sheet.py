#!/usr/bin/env python3
"""Renders every built-in look on the test photos, side by side with the original, for review.

    python3 tools/look-sheet.py OUT.png [photo ...]

Each look is developed at full size by the camera's own core (out/host/fltool, tools/build-host.sh
builds it) with the tone and grain its stock uses, then shrunk. A second sheet, OUT-grain.png, shows
a 1:1 crop of each, where the grain is visible.
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

# id, label, highlight, shadow, grain, grain size: as in src/com/latent/camera/Stock.java
LOOKS = [
    ('PORTRA4W', 'Portra 400', -1, -1, 4, 1),
    ('CLASSNEG', 'Classic Neg', 1, 1, 3, 1),
    ('CUBANNEG', 'Cuban Neg', -2, 1, 5, 2),
    ('TRIX400', 'Tri-X 400', 0, 1, 8, 2),
    ('PARTY800', 'Afterparty 800', -1, 2, 7, 3),
    ('NATURAL', 'Natural', 0, 0, 1, 1),
]
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TILE = 330
INK, DIM, BG = (244, 241, 234), (154, 149, 139), (0, 0, 0)


def develop(photo, look, tmp):
    lid, _, hl, sh, grain, size = look
    out = os.path.join(tmp, '%s-%s.jpg' % (os.path.basename(photo), lid))
    subprocess.check_call([os.path.join(ROOT, 'out/host/fltool'), 'process', photo, out,
                           '--lut', os.path.join(ROOT, 'assets/luts', lid + '.CUB'),
                           '--hl', str(hl), '--sh', str(sh), '--grain', str(grain), '--size', str(size)],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def main():
    out = sys.argv[1]
    photos = sys.argv[2:] or [os.path.join(ROOT, 'testdata', n) for n in ('STREET.JPG', 'HOUSE.JPG', 'PEOPLE.JPG')]
    font = ImageFont.truetype(os.path.join(ROOT, 'assets/fonts/inter-medium.ttf'), 17)
    cols = 1 + len(LOOKS)
    rows = []
    crops = []
    with tempfile.TemporaryDirectory() as tmp:
        for photo in photos:
            src = Image.open(photo).convert('RGB')
            w, h = src.size
            th = int(TILE * h / w)
            row = [src.resize((TILE, th), Image.LANCZOS)]
            # a 1:1 crop from the middle, for the grain sheet
            cx, cy, c = w // 2, h // 2, 300
            crow = [src.crop((cx - c // 2, cy - c // 2, cx + c // 2, cy + c // 2))]
            for look in LOOKS:
                dev = Image.open(develop(photo, look, tmp)).convert('RGB')
                row.append(dev.resize((TILE, th), Image.LANCZOS))
                crow.append(dev.crop((cx - c // 2, cy - c // 2, cx + c // 2, cy + c // 2)))
            rows.append(row)
            crops.append(crow)
    labels = ['Original'] + [l[1] for l in LOOKS]
    for name, grid, cell in ((out, rows, TILE), (out.replace('.png', '-grain.png'), crops, 300)):
        gap, top = 10, 40
        heights = [r[0].size[1] for r in grid]
        sheet = Image.new('RGB', (cols * cell + (cols + 1) * gap, top + sum(heights) + (len(grid) + 1) * gap), BG)
        d = ImageDraw.Draw(sheet)
        for i, label in enumerate(labels):
            d.text((gap + i * (cell + gap), 12), label, font=font, fill=INK if i else DIM)
        y = top
        for r, hgt in zip(grid, heights):
            for i, im in enumerate(r):
                sheet.paste(im, (gap + i * (cell + gap), y))
            y += hgt + gap
        sheet.save(name)
        print('wrote', name)


if __name__ == '__main__':
    main()
