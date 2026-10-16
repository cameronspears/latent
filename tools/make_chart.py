#!/usr/bin/env python3
"""Draws testdata/CHART.JPG: a colour chart for judging looks (the 24 ColorChecker patches in sRGB,
six skin tones from very light to very deep, a grey scale, and saturated primaries), 6000x4000 like
an A5100 frame, saved as a baseline 4:2:2 JPEG the core can read.

    python3 tools/make_chart.py testdata/CHART.JPG
"""
import sys

from PIL import Image, ImageDraw

CHECKER = [  # X-Rite ColorChecker Classic, sRGB D65 (approximate published values)
    (115, 82, 68), (194, 150, 130), (98, 122, 157), (87, 108, 67), (133, 128, 177), (103, 189, 170),
    (214, 126, 44), (80, 91, 166), (193, 90, 99), (94, 60, 108), (157, 188, 64), (224, 163, 46),
    (56, 61, 150), (70, 148, 73), (175, 54, 60), (231, 199, 31), (187, 86, 149), (8, 133, 161),
    (243, 243, 242), (200, 200, 200), (160, 160, 160), (122, 122, 121), (85, 85, 85), (52, 52, 52),
]
SKIN = [(246, 221, 203), (232, 190, 160), (208, 156, 120), (176, 120, 84), (128, 82, 56), (82, 52, 36)]
PRIMARY = [(230, 30, 30), (240, 140, 20), (240, 220, 30), (40, 170, 60), (30, 170, 200), (40, 70, 210), (170, 50, 190)]


def main():
    W, H = 6000, 4000
    im = Image.new('RGB', (W, H), (24, 24, 24))
    d = ImageDraw.Draw(im)
    pw, ph, g, x0, y0 = 860, 560, 100, 230, 200
    for i, c in enumerate(CHECKER):
        x, y = x0 + (i % 6) * (pw + g), y0 + (i // 6) * (ph + g)
        d.rectangle([x, y, x + pw, y + ph], fill=c)
    y = y0 + 4 * (ph + g) + 60
    sw = (W - 2 * x0 - 5 * g) // 6
    for i, c in enumerate(SKIN):
        d.rectangle([x0 + i * (sw + g), y, x0 + i * (sw + g) + sw, y + 420], fill=c)
    y += 520
    for i in range(W - 2 * x0):   # a smooth grey ramp, black to white
        v = int(round(255 * i / (W - 2 * x0 - 1)))
        d.line([(x0 + i, y), (x0 + i, y + 180)], fill=(v, v, v))
    im.save(sys.argv[1], quality=95, subsampling=1)


if __name__ == '__main__':
    main()
