#!/usr/bin/env python3
"""Generate Latent's 3D LUTs (.CUB = .cube, renamed for the camera's 8.3 file system).

Each look is a small model of a film printed on paper, applied to camera JPEGs shot with Sony's
Standard creative style (contrast/saturation 0, auto WB), in the order light takes through it:

  the camera's picture, back to light   (sRGB decoded to linear; the exposure the film is given)
  the film's colour sensitivity          (hue-selective moves in Oklab, before anything is developed)
  the negative                           (log exposure; interlayer effects push colours apart)
  the print                              (a characteristic curve per dye layer: toe, straight line, shoulder)
  the dyes                               (each print dye's unwanted absorptions: saturated colours get dense)
  the paper                              (its white and the depth of its black)

Tone lives in log exposure and density, as it does on film, so contrast and colour move together:
colours are richest in the midtones and quieten into the toe and the shoulder, saturated colours
darken instead of glowing, and a print's colour cast sits in the midtones while its whites stay white.
Black and white films take a filter-like channel mix first and are printed through one curve.

    python3 tools/make_luts.py OUTDIR
"""
import math
import os
import sys

import numpy as np

N = 33
MID = 0.18          # scene middle grey, linear
LUMA = np.array([0.2126, 0.7152, 0.0722])


# ---------------------------------------------------------------------------------------------
# colour helpers

def to_lin(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def to_srgb(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * v ** (1 / 2.4) - 0.055)


def smax(x, e):
    """A smooth max(x, 0) that stays above zero: x well above e passes, x below 0 tends to 0+."""
    return 0.5 * (x + np.sqrt(x * x + e * e))


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def bump(h, centre, width):
    """Smooth periodic weight: 1 at the centre hue, falling to ~0 by width degrees either side."""
    d = (h - centre + 180) % 360 - 180
    return np.exp(-0.5 * (d / (width / 2.0)) ** 2)


# Oklab (Björn Ottosson): perceptually even hue and chroma, so a hue move is the same size everywhere.
_M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                [0.2119034982, 0.6806995451, 0.1073969566],
                [0.0883024619, 0.2817188376, 0.6299787005]])
_M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                [1.9779984951, -2.4285922050, 0.4505937099],
                [0.0259040371, 0.7827717662, -0.8086757660]])
_M1i, _M2i = np.linalg.inv(_M1), np.linalg.inv(_M2)


def oklab(lin):
    return np.cbrt(lin @ _M1.T) @ _M2.T


def from_oklab(lab):
    return ((lab @ _M2i.T) ** 3) @ _M1i.T


def hue_of(srgb):
    lab = oklab(to_lin(np.array(srgb, dtype=float)))
    return math.degrees(math.atan2(lab[2], lab[1])) % 360


# hue references (Oklab hue angles of typical colours)
H = {
    'red': hue_of([0.80, 0.15, 0.12]),
    'orange': hue_of([0.90, 0.50, 0.15]),
    'skin': hue_of([0.85, 0.62, 0.50]),
    'yellow': hue_of([0.85, 0.80, 0.15]),
    'green': hue_of([0.30, 0.60, 0.20]),
    'cyan': hue_of([0.15, 0.60, 0.70]),
    'blue': hue_of([0.20, 0.35, 0.80]),
    'magenta': hue_of([0.70, 0.20, 0.60]),
}


# ---------------------------------------------------------------------------------------------
# the model

def sensitivity(lin, p):
    """The film's colour response, before development: global and hue-selective moves in Oklab.
    Each move is (hue, hue shift in degrees, chroma x, lightness x, width in degrees). Near-greys move
    less (they have no hue to speak of), so neutrals stay neutral."""
    lab = oklab(lin)
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b)
    h = np.degrees(np.arctan2(b, a)) % 360
    dh = np.zeros_like(h)
    cm = np.full_like(h, p.get('saturation', 1.0))
    lm = np.zeros_like(h)
    for name, shift, cmul, lmul, width in p.get('hues', []):
        w = bump(h, H[name], width)
        dh += shift * w
        cm *= 1 + (cmul - 1) * w
        lm += (lmul - 1) * w
    colour = smoothstep(0.0, 0.05, C)
    h2 = np.radians(h + dh * colour)
    C2 = C * (1 + (cm - 1) * colour)
    L2 = L * (1 + lm * colour)
    out = from_oklab(np.stack([L2, C2 * np.cos(h2), C2 * np.sin(h2)], axis=-1))
    return smax(out, 0.0005)


def print_curve(le, p, c):
    """Print density's mirror: log10 of the print's reflectance (relative to the paper's white) for
    log exposure le of dye layer c. A line of slope gamma through middle grey (moved by the layer's
    'mid' factor, the printer's light for that layer; its slope times 'hi' above middle grey), a
    shoulder that rolls into the paper's white, and a toe that settles on its black."""
    g = p['gamma'][c]
    # the straight line's slope can ease off above middle grey (hi < 1: a longer, gentler top)
    w = 0.25
    gh = g * p.get('hi', 1.0)
    line = g * le + (gh - g) * w * (np.logaddexp(0, le / w) - math.log(2)) + math.log10(MID * p['mid'][c])
    sh = p['shoulder']
    y = -sh * np.log10(1 + 10 ** (-line / sh))
    yb = math.log10(p['black'][c])
    st = p['toe']
    return yb + st * np.log10(1 + 10 ** ((y - yb) / st))


# unwanted absorptions of print dyes (cyan, magenta, yellow), per channel: row = channel, column = dye
DYES = np.array([[1.00, 0.06, 0.02],
                 [0.12, 1.00, 0.06],
                 [0.08, 0.30, 1.00]])


def develop(rgb, p):
    """The print's reflectance (linear, per channel) for camera sRGB values rgb."""
    # the camera's picture back to light, and the exposure the film is given
    lin = to_lin(rgb) * np.array(p.get('wb', [1, 1, 1]))
    lin = lin * 2 ** p.get('ev', 0.0)

    if 'mono' in p:
        # black and white: the filter, then one emulsion and one paper
        e = smax(lin @ np.array(p['mono']), 0.0005)
        le = np.log10((e + p.get('flare', 0.0004)) / MID)
        y = np.stack([print_curve(le, p, c) for c in range(3)], axis=-1)
    else:
        lin = sensitivity(lin, p)
        le = np.log10((lin + p.get('flare', 0.0004)) / MID)
        # interlayer effects: each layer's development held back by the others' pushes colours apart
        mean = le @ LUMA
        le = le + p.get('interlayer', 0.0) * (le - mean[..., None])
        y = np.stack([print_curve(le[..., c], p, c) for c in range(3)], axis=-1)
        # the print's dyes, with their unwanted absorptions (in density; rows sum to one, greys stay grey)
        k = p.get('dyes', 0.0)
        X = np.eye(3) + k * (DYES - np.eye(3))
        X = X / X.sum(axis=1, keepdims=True)
        y = y @ X.T
    return 10 ** y


def apply_look(rgb, p):
    """The look: the print, scaled so the camera's white is the paper's white, its black lifted to the
    paper's black, back to sRGB."""
    # the same film printed neutral (no colour balance, every layer alike) sets the paper's white, so the
    # print's colour balance shows as it would: in the midtones, easing off into the shoulder and the toe
    neutral = dict(p, wb=[1, 1, 1], mid=[np.mean(p['mid'])] * 3, gamma=[np.mean(p['gamma'])] * 3,
                   black=[np.mean(p['black'])] * 3)
    white = develop(np.ones(3), neutral)[1]
    out = develop(rgb, p) / white
    fade = np.array(p.get('fade', [0, 0, 0]))     # the paper's black: a lift in light, tinted per layer
    out = (fade + (1 - fade) * out) * np.array(p.get('white', [1, 1, 1]))
    if 'finish' in p:
        out = finish(out, p['finish'])
    return np.clip(to_srgb(out), 0, 1)


def finish(lin, f):
    """A painter's finish over the print (Painterly): in Oklab, by the print's own lightness (f['L']), muted colours
    lifted toward f['gain'] (already-strong ones less, so nothing turns neon) and the tones tinted by f['a'], f['b']."""
    lab = oklab(np.maximum(lin, 0))
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b)
    gain = np.interp(L, f['L'], f['gain'])
    gain = 1 + (gain - 1) * (1 - smoothstep(0.06, 0.20, C))
    a = a * gain + np.interp(L, f['L'], f['a'])
    b = b * gain + np.interp(L, f['L'], f['b'])
    return smax(from_oklab(np.stack([L, a, b], axis=-1)), 0.0002)


# ---------------------------------------------------------------------------------------------
# the films

LOOKS = {
    # Natural colour in the Hasselblad spirit: faithful hues, true whites, colour with depth rather than
    # brightness, and a long, soft roll into the highlights. The quiet one.
    'NATURAL': dict(
        title='Natural',
        gamma=[1.02, 1.02, 1.02], mid=[0.86, 0.86, 0.86],
        shoulder=0.12, toe=0.22, black=[0.0012, 0.0012, 0.0012], fade=[0.0008, 0.0008, 0.0008],
        interlayer=0.14, dyes=0.55, saturation=1.0,
        hues=[
            ('skin', 1, 0.96, 1.01, 40),
            ('green', -4, 0.94, 0.98, 60),
            ('blue', 0, 1.02, 0.97, 50),
        ],
        white=[1.0, 1.0, 1.0],
    ),
    # Kodak Portra 400, exposed generously: soft and airy, a matte black, creamy warm highlights,
    # peachy-golden skin kept calm, greens toward olive, soft blues.
    'PORTRA4W': dict(
        title='Portra 400',
        ev=0.2,
        gamma=[0.92, 0.92, 0.92], hi=0.9, mid=[1.0, 0.90, 0.80],
        shoulder=0.16, toe=0.30, black=[0.0016, 0.0016, 0.0016], fade=[0.0040, 0.0042, 0.0044],
        interlayer=0.10, dyes=0.6, saturation=1.0,
        hues=[
            ('red', 5, 0.95, 1.0, 50),
            ('skin', 6, 1.04, 1.02, 45),
            ('yellow', -4, 0.90, 1.0, 50),
            ('green', -12, 0.80, 0.98, 70),
            ('cyan', -4, 0.90, 1.0, 50),
            ('blue', -4, 0.88, 1.02, 60),
        ],
        white=[1.0, 0.99, 0.97],
    ),
    # Fuji's Classic Negative pushed the way Osan Bilgi's "Classic Cuban Neg" does: hard tone, deep teal
    # shadows, warm golden highlights, brick reds, mustard yellows, olive-teal greens, cyan skies.
    'CUBANNEG': dict(
        title='Classic Cuban Neg',
        gamma=[1.21, 1.15, 1.09], hi=0.92, mid=[0.76, 0.72, 0.64],
        shoulder=0.08, toe=0.22, black=[0.0008, 0.0010, 0.0010], fade=[0.0012, 0.0030, 0.0034],
        interlayer=0.18, dyes=0.8, saturation=1.05,
        hues=[
            ('red', -4, 1.02, 0.96, 50),
            ('skin', 2, 0.78, 1.0, 40),
            ('yellow', -8, 0.85, 1.0, 50),
            ('green', 20, 0.72, 0.96, 70),
            ('cyan', 2, 1.0, 1.0, 50),
            ('blue', -5, 0.95, 0.96, 60),
            ('magenta', -8, 0.85, 1.0, 50),
        ],
        white=[0.95, 0.90, 0.80],
    ),
    # Painterly, after Alex Kittoe's photographs and collages: the world remembered rather than recorded. A matte,
    # high-key print (blacks lifted to a warm umber, whites held below the paper's own), colour kept in the shadows,
    # azure skies, golden light, sage and olive greens, lavender-pink twilight, rich teal water. Its texture is a
    # canvas weave (grain size 4), as his collages are printed.
    'PAINTRLY': dict(
        title='Painterly',
        ev=0.4,
        gamma=[0.86, 0.86, 0.86], hi=0.75, mid=[1.0, 0.97, 0.90],
        shoulder=0.22, toe=0.30, black=[0.0012, 0.0012, 0.0012], fade=[0.0052, 0.0047, 0.0035],
        interlayer=0.2, dyes=0.4, saturation=1.0,
        hues=[
            ('red', 4, 0.85, 1.0, 50),        # reds toward coral, quieter
            ('orange', -2, 1.0, 1.02, 40),    # amber light
            ('skin', 2, 0.92, 1.03, 40),      # golden, a touch lighter
            ('yellow', -2, 0.95, 1.0, 50),
            ('green', -6, 0.85, 1.02, 70),    # sage and olive
            ('cyan', -2, 1.0, 1.0, 50),       # teal water stays rich
            ('blue', -8, 0.78, 1.06, 60),     # skies azure, lighter and softer
            ('magenta', 8, 0.95, 1.04, 50),   # twilight toward lavender-pink
        ],
        white=[0.80, 0.77, 0.72],
        # his tones measured (236 photographs): about 1.5x a camera's colour in the shadows and midtones, warm
        # there, near neutral in the highlights
        finish=dict(L=[0.15, 0.35, 0.55, 0.75, 0.95],
                    gain=[1.45, 1.4, 1.3, 1.2, 1.0],
                    a=[0.0, -0.002, -0.001, 0.003, 0.002],
                    b=[0.004, 0.006, 0.004, 0.002, 0.0]),
    ),
    # Kodak Tri-X 400 pushed: an orange-filter mix (darker skies, glowing skin), a steep print with inky
    # blacks and bright paper whites, and the faintest warm paper tone. Heavy grain does the rest.
    'TRIX400': dict(
        title='Tri-X 400',
        mono=[0.46, 0.50, 0.04],
        gamma=[1.6, 1.6, 1.6], hi=0.72, mid=[0.95, 0.95, 0.95],
        shoulder=0.12, toe=0.18, black=[0.0006, 0.0006, 0.0006], fade=[0.0008, 0.0007, 0.0006],
        white=[0.97, 0.955, 0.935],
    ),
}


# The looks' version, in every file's first line. Latent replaces a look an earlier version put on the card when
# the first lines differ (Library.stale), so raise it whenever the looks change.
LOOKS_VERSION = 2


def write_cube(path, title, lut):
    with open(path, 'w', newline='\n') as f:
        f.write('# Latent look %d. Input: Sony Standard creative style JPEG (sRGB), shot neutral.\n' % LOOKS_VERSION)
        f.write('TITLE "%s"\n' % title)
        f.write('LUT_3D_SIZE %d\n' % N)
        f.write('DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n')
        # .cube order: red changes fastest, then green, then blue
        for b in range(N):
            for g in range(N):
                for r in range(N):
                    v = lut[b, g, r]
                    f.write('%.5f %.5f %.5f\n' % (v[0], v[1], v[2]))


def build(p):
    axis = np.linspace(0, 1, N)
    b, g, r = np.meshgrid(axis, axis, axis, indexing='ij')
    lut = apply_look(np.stack([r, g, b], axis=-1), p)   # [b, g, r, rgb]
    diag = np.array([lut[i, i, i] @ np.array([0.299, 0.587, 0.114]) for i in range(N)])
    assert np.all(np.diff(diag) > 0), p['title'] + ': grey ramp not monotonic'
    return lut


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, p in LOOKS.items():
        path = os.path.join(out, name + '.CUB')
        write_cube(path, p['title'], build(p))
        print('wrote', path)


if __name__ == '__main__':
    main()
