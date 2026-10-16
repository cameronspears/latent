#!/usr/bin/env python3
"""Check a native library will load with Android 2.3's dynamic linker:
32-bit ARM ELF, a SysV .hash section (2.3 cannot read GNU hash), no text relocations,
and only imports that libc / libm / libdl on the camera provide.

    python3 tools/check_so.py libfilmlab.so
"""
import struct
import sys

ALLOWED_LIBS = {'libc.so', 'libm.so', 'libdl.so', 'libstdc++.so'}  # all in /system/lib since Android 1.x


def main():
    data = open(sys.argv[1], 'rb').read()
    errors = []
    if data[:4] != b'\x7fELF' or data[4] != 1 or data[5] != 1:
        sys.exit('not a 32-bit little-endian ELF')
    e_machine = struct.unpack_from('<H', data, 18)[0]
    if e_machine != 40:
        errors.append('not ARM (e_machine=%d)' % e_machine)
    shoff, = struct.unpack_from('<I', data, 32)
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', data, 46)
    secs = [struct.unpack_from('<IIIIIIIIII', data, shoff + i * shentsize) for i in range(shnum)]
    strtab = secs[shstrndx]
    name = lambda off: data[strtab[4] + off:data.index(b'\0', strtab[4] + off)].decode()
    by_name = {name(s[0]): s for s in secs}
    if '.hash' not in by_name:
        errors.append('no SysV .hash section (Android 2.3 needs it)')
    dyn = by_name.get('.dynamic')
    dynstr = by_name.get('.dynstr')
    needed = []
    if dyn and dynstr:
        for i in range(0, dyn[5], 8):
            tag, val = struct.unpack_from('<iI', data, dyn[4] + i)
            if tag == 0:
                break
            if tag == 1:  # DT_NEEDED
                needed.append(data[dynstr[4] + val:data.index(b'\0', dynstr[4] + val)].decode())
            if tag == 22 or (tag == 30 and val & 4):  # DT_TEXTREL / DF_TEXTREL
                errors.append('has text relocations')
    for lib in needed:
        if lib not in ALLOWED_LIBS:
            errors.append('needs %s, which the camera may not have' % lib)
    print('  ELF ARM, needs: %s, hash: %s' % (', '.join(needed), 'sysv' if '.hash' in by_name else 'none'))
    if errors:
        sys.exit('check_so: ' + '; '.join(errors))


if __name__ == '__main__':
    main()
