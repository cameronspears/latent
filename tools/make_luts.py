#!/usr/bin/env python3
"""Generate Latent's 3D LUTs (.CUB = .cube, renamed for the camera's 8.3 file system).

The looks are built from a small parametric film model, applied to camera JPEGs shot with Sony's
Standard creative style (contrast/saturation 0, auto WB):

  white balance shift  ->  per-channel film curve  ->  hue-selective hue/saturation moves
  ->  split toning (shadows / highlights)  ->  black lift / highlight roll-off

    python3 tools/make_luts.py OUTDIR
"""
import math
import os
import sys

import numpy as np

N = 33


# ---------------------------------------------------------------------------------------------
# colour helpers (sRGB-encoded values in 0..1 throughout, except where noted)

def to_lin(v):
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def to_srgb(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * v ** (1 / 2.4) - 0.055)


def luma(rgb):
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def to_ycc(rgb):
    y = luma(rgb)
    return y, rgb[..., 2] - y, rgb[..., 0] - y          # Y, B-Y, R-Y


def from_ycc(y, cb, cr):
    r = cr + y
    b = cb + y
    g = (y - 0.299 * r - 0.114 * b) / 0.587
    return np.stack([r, g, b], axis=-1)


def hue_of(rgb):
    _, cb, cr = to_ycc(np.array(rgb, dtype=float))
    return math.degrees(math.atan2(cr, cb)) % 360


def bump(h, centre, width):
    """Smooth periodic weight, 1 at centre hue, ~0 beyond width degrees."""
    d = (h - centre + 180) % 360 - 180
    return np.exp(-0.5 * (d / (width / 2.0)) ** 2)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def film_curve(x, contrast, pivot, toe, shoulder):
    """S-curve in sRGB space: contrast around pivot, soft toe (lifts deep shadows by 'toe'),
    soft shoulder (compresses the top 'shoulder' of the range). Monotonic for sane inputs."""
    y = pivot + (x - pivot) * contrast
    # blend back to identity near the ends so the curve still reaches 0 and 1
    lo = smoothstep(0, pivot, x)
    hi = 1 - smoothstep(pivot, 1, x)
    y = x + (y - x) * (lo * hi) ** 0.5
    y = y + toe * (1 - x) ** 4                      # lift / fade the blacks
    y = y - shoulder * x ** 4 * (1 - x) * 4          # roll the highlights off
    return np.clip(y, 0, 1)


# ---------------------------------------------------------------------------------------------
# hue references (hue angles in this Y/B-Y/R-Y space)

H = {
    'red': hue_of([0.8, 0.15, 0.12]),
    'orange': hue_of([0.9, 0.5, 0.15]),
    'skin': hue_of([0.85, 0.62, 0.5]),
    'yellow': hue_of([0.85, 0.8, 0.15]),
    'green': hue_of([0.3, 0.6, 0.2]),
    'cyan': hue_of([0.15, 0.6, 0.7]),
    'blue': hue_of([0.2, 0.35, 0.8]),
    'magenta': hue_of([0.7, 0.2, 0.6]),
}


def apply_look(rgb, p):
    # 1. white balance (linear light): amber/blue and green/magenta like the camera's WB shift
    lin = to_lin(rgb)
    lin = lin * np.array(p['wb'])
    lin = lin / max(p['wb'])                          # never push white past clipping
    # exposure, with a soft knee so brightened highlights roll off instead of clipping
    lin = lin * p.get('exposure', 1.0)
    k = 0.75
    lin = np.where(lin < k, lin, k + (1 - k) * np.tanh((lin - k) / (1 - k)))
    rgb = to_srgb(lin)

    if 'mono' in p:
        # black and white: a filter-like channel mix (in linear light, as on film), one curve, a tone
        wr, wg, wb_ = p['mono']
        lin = to_lin(rgb)
        g = to_srgb(np.clip(lin[..., 0] * wr + lin[..., 1] * wg + lin[..., 2] * wb_, 0, 1))
        g = film_curve(g, p['contrast'], p['pivot'], p['toe'], p['shoulder'])
        y = g
        ws = (1 - smoothstep(0.0, 0.5, y)) ** 1.5
        wh = smoothstep(0.5, 1.0, y) ** 1.5
        cb = ws * p['shadow_tint'][0] + wh * p['highlight_tint'][0]
        cr = ws * p['shadow_tint'][1] + wh * p['highlight_tint'][1]
        rgb = from_ycc(y, cb, cr)
        rgb = p['black'] + rgb * (p['white'] - p['black'])
        return np.clip(rgb, 0, 1)

    # 2. film curve per channel (per-channel curves give film's saturation-with-contrast)
    rgb = film_curve(rgb, p['contrast'], p['pivot'], p['toe'], p['shoulder'])

    # 3. hue-selective moves
    y, cb, cr = to_ycc(rgb)
    chroma = np.hypot(cb, cr)
    h = np.degrees(np.arctan2(cr, cb)) % 360
    dh = np.zeros_like(h)
    sat = np.full_like(h, p['saturation'])
    for name, shift, satmul, width in p['hues']:
        w = bump(h, H[name], width)
        dh += shift * w
        sat *= 1 + (satmul - 1) * w
    # low-chroma colours (near-neutrals) move less, so greys stay grey
    neutral = smoothstep(0.0, 0.08, chroma)
    h2 = np.radians(h + dh * neutral)
    chroma2 = chroma * (1 + (sat - 1) * neutral)
    # gentle chroma compression at the top so strong colours don't clip harshly
    chroma2 = chroma2 / (1 + np.maximum(chroma2 - 0.35, 0) * p.get('chroma_knee', 0.8))
    cb, cr = chroma2 * np.cos(h2), chroma2 * np.sin(h2)

    # 4. split toning: tint shadows and highlights (B-Y, R-Y offsets)
    ws = (1 - smoothstep(0.0, 0.5, y)) ** 1.5
    wh = smoothstep(0.5, 1.0, y) ** 1.5
    cb = cb + ws * p['shadow_tint'][0] + wh * p['highlight_tint'][0]
    cr = cr + ws * p['shadow_tint'][1] + wh * p['highlight_tint'][1]
    rgb = from_ycc(y, cb, cr)

    # 5. final black point / white point (matte look)
    rgb = p['black'] + rgb * (p['white'] - p['black'])
    return np.clip(rgb, 0, 1)


LOOKS = {
    # Classic Negative, pushed the way Osan Bilgi's "Classic Cuban Neg" does: warm (R+4 B-5),
    # Color +4, Highlight -2, Shadow +1, DR400. Deep teal-leaning shadows, warm creamy highlights,
    # brick reds, olive-teal greens, cyan skies, and an overall golden cast.
    'CUBANNEG': dict(
        title='Classic Cuban Neg',
        wb=[1.10, 1.0, 0.84],
        contrast=1.28, pivot=0.42, toe=0.015, shoulder=0.10,
        saturation=1.18,
        hues=[
            ('red', -4, 1.0, 50),        # reds toward crimson/brick
            ('orange', -2, 1.0, 40),
            ('skin', 3, 0.82, 40),       # golden, not pink, and calmer than the rest
            ('yellow', -10, 0.85, 50),   # Classic Neg's slightly green-mustard yellows
            ('green', 22, 0.70, 70),     # greens toward olive-teal, muted
            ('cyan', 4, 1.10, 50),
            ('blue', -12, 0.95, 60),     # skies toward cyan-teal
            ('magenta', -8, 0.85, 50),
        ],
        shadow_tint=(0.010, -0.014),     # a hint of teal in the shadows
        highlight_tint=(-0.035, 0.020),  # warm, golden highlights
        black=0.020, white=0.985,
    ),
    # Kodak Portra 400 after Fuji X Weekly's recipe (Classic Chrome base, H-1 S-1, Color +2,
    # R+4 B-5, +2/3 EV): soft low contrast, slightly matte blacks, warm creamy highlights,
    # peachy skin, yellow-green greens, soft cyan-leaning blues.
    'PORTRA4W': dict(
        title='Portra 400 Warm',
        wb=[1.08, 1.0, 0.90],
        exposure=1.22,                   # Portra is shot +2/3 EV; brighter, airier
        contrast=0.92, pivot=0.45, toe=0.03, shoulder=0.04,
        saturation=1.12,
        chroma_knee=0.6,
        hues=[
            ('red', 4, 1.0, 50),
            ('orange', 2, 1.0, 40),
            ('skin', -3, 0.92, 45),      # peachy rather than orange, smooth
            ('yellow', 6, 0.95, 50),
            ('green', -12, 0.85, 70),    # greens toward yellow-green, softer
            ('cyan', -4, 0.92, 50),
            ('blue', -6, 0.92, 60),      # cyan-leaning, gentle skies
            ('magenta', 0, 0.9, 50),
        ],
        shadow_tint=(0.0, 0.004),
        highlight_tint=(-0.025, 0.018),  # creamy warm highlights
        black=0.03, white=0.99,
    ),
    # Fuji Classic Negative itself (Superia's palette): hard tone, restrained colour, reds deep and a
    # touch orange, greens pulled toward teal and quieted, cyan-teal skies, teal-cool shadows, warm
    # slightly magenta highlights, and a faint fade in the deepest blacks. Cooler and harder than
    # Cuban Neg, which is this film pushed warm.
    'CLASSNEG': dict(
        title='Classic Negative',
        wb=[1.0, 1.0, 0.97],
        contrast=1.34, pivot=0.40, toe=0.022, shoulder=0.08,
        saturation=0.96,
        chroma_knee=0.9,
        hues=[
            ('red', 6, 1.12, 45),        # deep, slightly orange reds
            ('orange', 3, 1.0, 40),
            ('skin', 4, 0.84, 40),
            ('yellow', -12, 0.80, 50),   # mustard-green yellows
            ('green', 28, 0.62, 70),     # teal, muted greens
            ('cyan', 6, 1.05, 50),
            ('blue', -16, 0.90, 60),     # cyan-teal skies
            ('magenta', -10, 0.80, 50),
        ],
        shadow_tint=(0.018, -0.022),     # teal shadows
        highlight_tint=(-0.012, 0.016),  # warm, a little magenta
        black=0.028, white=0.975,
    ),
    # Kodak Tri-X 400 pushed: a yellow-filter mix (darker skies, glowing skin), a steep curve with
    # inky blacks and bright paper whites, the faintest warm paper tone. Heavy grain does the rest.
    'TRIX400': dict(
        title='Tri-X 400',
        wb=[1.0, 1.0, 1.0],
        mono=(0.46, 0.50, 0.04),         # orange filter: skies darken, skin glows, foliage holds
        contrast=1.55, pivot=0.42, toe=0.0, shoulder=0.05,
        shadow_tint=(-0.004, 0.002), highlight_tint=(-0.008, 0.006),
        black=0.012, white=0.985,
    ),
    # A night out with the flash on (Superia 800 / Gold-style party snaps): punchy contrast, inky
    # backgrounds, warm glowing skin, saturated reds and magentas, a cool green cast in the shadows
    # from the ambient light, slightly hot highlights. Big grain.
    'PARTY800': dict(
        title='Afterparty 800',
        wb=[1.05, 1.0, 0.95],
        contrast=1.36, pivot=0.38, toe=0.0, shoulder=0.05,
        saturation=1.10,
        chroma_knee=1.0,
        hues=[
            ('red', -2, 1.08, 45),
            ('orange', 2, 1.0, 40),
            ('skin', -3, 0.80, 45),      # warm and glowing, but skin, not orange
            ('yellow', -4, 0.95, 50),
            ('green', 12, 0.88, 60),
            ('cyan', 0, 1.0, 50),
            ('blue', 4, 0.90, 60),       # blues a touch violet, not neon
            ('magenta', 4, 1.12, 50),
        ],
        shadow_tint=(0.006, -0.024),     # green-cyan ambient in the shadows
        highlight_tint=(-0.018, 0.012),  # flash-warm highlights
        black=0.0, white=1.0,
    ),
    # Natural colour in the Hasselblad spirit: faithful hues, true whites, a little extra depth in
    # colour and a long, smooth highlight roll-off. The quiet one.
    'NATURAL': dict(
        title='Natural',
        wb=[1.0, 1.0, 0.99],
        contrast=1.06, pivot=0.45, toe=0.006, shoulder=0.12,
        saturation=1.06,
        chroma_knee=0.5,
        hues=[
            ('skin', -1, 0.97, 40),
            ('green', -3, 0.97, 60),
            ('blue', -2, 1.04, 60),
        ],
        shadow_tint=(0.0, 0.0),
        highlight_tint=(-0.004, 0.002),
        black=0.008, white=0.995,
    ),
}


def write_cube(path, title, lut):
    with open(path, 'w', newline='\n') as f:
        f.write('# Latent look. Input: Sony Standard creative style JPEG (sRGB), shot neutral.\n')
        f.write('TITLE "%s"\n' % title)
        f.write('LUT_3D_SIZE %d\n' % N)
        f.write('DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n')
        # .cube order: red changes fastest, then green, then blue
        for b in range(N):
            for g in range(N):
                for r in range(N):
                    v = lut[b, g, r]
                    f.write('%.5f %.5f %.5f\n' % (v[0], v[1], v[2]))


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    axis = np.linspace(0, 1, N)
    b, g, r = np.meshgrid(axis, axis, axis, indexing='ij')
    grid = np.stack([r, g, b], axis=-1)                # [b, g, r, rgb]
    for name, p in LOOKS.items():
        lut = apply_look(grid, p)
        # sanity: neutral axis must stay monotonic
        diag = np.array([luma(lut[i, i, i]) for i in range(N)])
        assert np.all(np.diff(diag) > 0), name + ': grey ramp not monotonic'
        path = os.path.join(out, name + '.CUB')
        write_cube(path, p['title'], lut)
        print('wrote', path)


if __name__ == '__main__':
    main()
