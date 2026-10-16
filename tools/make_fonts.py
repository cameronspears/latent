#!/usr/bin/env python3
"""Builds the app's fonts (assets/fonts) from the OFL sources: static Inter instances, subset to
the characters the UI uses, with tabular (fixed-width) digits baked in so counters don't jitter.
Android 2.3 can't do variable fonts or OpenType features, hence the static, pre-baked files.

    python3 tools/make_fonts.py Inter[opsz,wght].ttf IBMPlexMono-Medium.ttf assets/fonts
"""
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

TEXT = ''.join(chr(c) for c in range(0x20, 0x7F)) + '·–—…‹›°×’“”'


def tabular(font):
    """Point the digits at Inter's tabular glyphs (zero.tf ...)."""
    names = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine']
    glyphs = set(font.getGlyphOrder())
    for table in font['cmap'].tables:
        for i, n in enumerate(names):
            if n + '.tf' in glyphs and (0x30 + i) in table.cmap:
                table.cmap[0x30 + i] = n + '.tf'


def build(src, out, axes=None, tnum=False):
    font = TTFont(src)
    if axes:
        font = instancer.instantiateVariableFont(font, axes)
    if tnum:
        tabular(font)
    opts = subset.Options()
    opts.layout_features = []          # no shaping on Android 2.3 anyway
    opts.name_IDs = ['*']
    opts.notdef_outline = True
    s = subset.Subsetter(opts)
    s.populate(text=TEXT)
    s.subset(font)
    font.save(out)
    print('%-28s %6d bytes' % (os.path.basename(out), os.path.getsize(out)))


def main():
    inter, plex, out = sys.argv[1:4]
    os.makedirs(out, exist_ok=True)
    build(inter, os.path.join(out, 'inter-display-light.ttf'), {'wght': 300, 'opsz': 32}, tnum=True)
    build(inter, os.path.join(out, 'inter-regular.ttf'), {'wght': 400, 'opsz': 14}, tnum=True)
    build(inter, os.path.join(out, 'inter-medium.ttf'), {'wght': 500, 'opsz': 14}, tnum=True)
    build(plex, os.path.join(out, 'plex-mono-medium.ttf'))


if __name__ == '__main__':
    main()
