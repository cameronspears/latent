#!/usr/bin/env python3
"""Print a JPEG's marker layout and EXIF thumbnail facts; optionally extract the thumbnail.

    python3 tools/jpeginfo.py FILE.JPG [--thumb OUT.jpg]
"""
import struct
import sys


def markers(data):
    i = 2
    out = []
    while i < len(data) - 4:
        if data[i] != 0xFF:
            break
        m = data[i + 1]
        if m == 0xDA:  # start of scan
            out.append((m, i, 0))
            break
        ln = struct.unpack('>H', data[i + 2:i + 4])[0]
        out.append((m, i, ln))
        i += 2 + ln
    return out


def exif_thumb(app1):
    if app1[:6] != b'Exif\0\0':
        return None
    t = app1[6:]
    le = t[:2] == b'II'
    e = '<' if le else '>'
    u16 = lambda o: struct.unpack(e + 'H', t[o:o + 2])[0]
    u32 = lambda o: struct.unpack(e + 'I', t[o:o + 4])[0]
    ifd0 = u32(4)
    n0 = u16(ifd0)
    info = {}
    for k in range(n0):
        o = ifd0 + 2 + 12 * k
        tag = u16(o)
        if tag == 0x0112:
            info['orientation'] = u16(o + 8)
        if tag in (0x010F, 0x0110):
            cnt, off = u32(o + 4), u32(o + 8)
            info['make' if tag == 0x010F else 'model'] = t[off:off + cnt].rstrip(b'\0').decode('latin1') if cnt > 4 else ''
    ifd1 = u32(ifd0 + 2 + 12 * n0)
    if not ifd1:
        return info
    n1 = u16(ifd1)
    off = ln = None
    for k in range(n1):
        o = ifd1 + 2 + 12 * k
        tag = u16(o)
        if tag == 0x0201:
            off = u32(o + 8)
        if tag == 0x0202:
            ln = u32(o + 8)
    info['thumb'] = (off, ln, len(t))
    if off is not None:
        info['thumb_bytes'] = t[off:off + ln]
    return info


def main():
    path = sys.argv[1]
    data = open(path, 'rb').read()
    print(path, len(data), 'bytes')
    for m, at, ln in markers(data):
        name = {0xE0: 'APP0', 0xE1: 'APP1', 0xE2: 'APP2', 0xDB: 'DQT', 0xC0: 'SOF0', 0xC2: 'SOF2',
                0xC4: 'DHT', 0xDD: 'DRI', 0xDA: 'SOS', 0xFE: 'COM'}.get(m, 'FF%02X' % m)
        tag = ''
        if 0xE0 <= m <= 0xEF:
            tag = data[at + 4:at + 10].split(b'\0')[0].decode('latin1', 'replace')
        if m in (0xC0, 0xC2):
            h, w = struct.unpack('>HH', data[at + 5:at + 9])
            nc = data[at + 9]
            samp = [data[at + 11 + 3 * c] for c in range(nc)]
            tag = '%dx%d comps=%d samp=%s' % (w, h, nc, ['%dx%d' % (s >> 4, s & 15) for s in samp])
        print('  %-5s @%-8d len=%-6d %s' % (name, at, ln, tag))
        if m == 0xE1:
            info = exif_thumb(data[at + 4:at + 2 + ln])
            if info:
                tb = info.pop('thumb_bytes', None)
                print('        exif:', info)
                if tb is not None:
                    print('        thumb starts', tb[:2].hex(), 'ends', tb[-2:].hex())
                    if '--thumb' in sys.argv:
                        open(sys.argv[sys.argv.index('--thumb') + 1], 'wb').write(tb)


if __name__ == '__main__':
    main()
