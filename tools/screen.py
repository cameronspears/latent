#!/usr/bin/env python3
"""LATENT/SCREEN.RAW (the viewfinder's framebuffer, kept once a visit) to pictures, one per likely layout.

  python3 tools/screen.py /Volumes/Untitled/LATENT/SCREEN.RAW out/screen

The camera's OSD is 640x480; in 16-bit mode each pixel is two bytes (RGBA4444 or ARGB4444, little-endian),
and a full-colour page four. Writes a .ppm for each layout the size allows; macOS Preview opens them."""
import os, struct, sys

def nib(v, shift):
    return ((v >> shift) & 15) * 17

def write(path, w, h, rgb):
    with open(path, 'wb') as f:
        f.write(b'P6\n%d %d\n255\n' % (w, h))
        f.write(bytes(rgb))
    print(path)

def main(raw, out, w=640, h=480):
    data = open(raw, 'rb').read()
    os.makedirs(out, exist_ok=True)
    print(len(data), 'bytes')
    page = w * h * 2
    for p in range(min(2, len(data) // page)):
        px = struct.unpack_from('<%dH' % (w * h), data, p * page)
        for name, (rs, gs, bs) in {'rgba4444': (12, 8, 4), 'argb4444': (8, 4, 0)}.items():
            rgb = bytearray()
            for v in px:
                rgb += bytes((nib(v, rs), nib(v, gs), nib(v, bs)))
            write(os.path.join(out, 'page%d-%s.ppm' % (p, name)), w, h, rgb)
    if len(data) >= w * h * 4:
        rgb = bytearray()
        for i in range(w * h):
            b = data[i * 4:i * 4 + 4]
            rgb += bytes((b[0], b[1], b[2]))
        write(os.path.join(out, 'page0-abgr8888.ppm'), w, h, rgb)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'out/screen')
