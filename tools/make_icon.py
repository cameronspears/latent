#!/usr/bin/env python3
"""Draws the app icon (res/drawable/icon.png, 96x96) from the camera back's frame counter: a run
of exposure ticks on matte black, shot frames in ink, the current frame in the film's orange.

    python3 tools/make_icon.py res/drawable/icon.png [preview.png]

Drawn at 1024 px and reduced, so edges stay clean at 96 px. Colours are the app's own (Ui.java).
"""
import sys
from PIL import Image, ImageDraw

S = 1024
BG = (12, 12, 11)          # matte black body
RIM = (44, 42, 39)         # hairline, so the icon holds its shape on a black menu
INK = (244, 241, 234)      # Ui.INK: shot frames
LEFT = (70, 67, 62)        # frames still to shoot
ACCENT = (240, 116, 58)    # the current frame (Cuban Neg's accent)


def icon():
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    radius = int(S * 0.2237)                       # the familiar rounded-square proportion
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=radius, fill=255)
    body = Image.new('RGBA', (S, S), RIM + (255,))
    inner = Image.new('RGBA', (S, S), BG + (255,))
    light = Image.new('L', (S, S), 0)              # a little light from above, like anodised metal
    for y in range(S):
        ImageDraw.Draw(light).line([(0, y), (S, y)], fill=int(16 * (1 - y / S) ** 2))
    inner = Image.alpha_composite(inner, Image.merge('RGBA', (*[Image.new('L', (S, S), 255)] * 3, light)))
    rim = int(S * 0.012)
    imask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(imask).rounded_rectangle([rim, rim, S - 1 - rim, S - 1 - rim], radius=radius - rim, fill=255)
    body.paste(inner, (0, 0), imask)
    im.paste(body, (0, 0), mask)

    d = ImageDraw.Draw(im)
    n, current = 7, 3                              # seven ticks, the fourth is the frame in hand
    gap, w = S * 0.098, S * 0.036
    x0, cy = S / 2 - gap * (n - 1) / 2, S * 0.5
    for i in range(n):
        x = x0 + i * gap
        h = S * (0.17 if i in (0, n - 1) else 0.12)    # the roll's ends a little taller, like the counter's majors
        col = INK if i < current else LEFT
        if i == current:
            col, h, w_i = ACCENT, S * 0.30, w * 1.15
        else:
            w_i = w
        d.rounded_rectangle([x - w_i / 2, cy - h / 2, x + w_i / 2, cy + h / 2], radius=w_i / 2, fill=col)
    return im


if __name__ == '__main__':
    big = icon()
    big.resize((96, 96), Image.LANCZOS).save(sys.argv[1], optimize=True)
    if len(sys.argv) > 2:
        big.save(sys.argv[2])
