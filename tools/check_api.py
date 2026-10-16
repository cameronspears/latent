#!/usr/bin/env python3
"""Fail the build if compiled classes use an Android/Java API newer than the camera's (API 10).

Reads every method/field reference with `javap -c -p`, looks it up in the SDK's api-versions.xml
and reports anything whose 'since' is above the limit.

    python3 tools/check_api.py api-versions.xml out/classes 10
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def load(path):
    classes = {}
    for c in ET.parse(path).getroot().iter('class'):
        name = c.get('name')
        since = int(c.get('since', '1'))
        members = {}
        for m in c:
            if m.tag in ('method', 'field'):
                members[m.get('name')] = int(m.get('since', since))
        supers = [e.get('name') for e in c if e.tag in ('extends', 'implements')]
        classes[name] = (since, members, supers)
    return classes


def member_since(classes, cls, member, seen=None):
    """Lowest API level at which cls (or a superclass) declares member; None if unknown."""
    seen = seen or set()
    if cls in seen or cls not in classes:
        return None
    seen.add(cls)
    since, members, supers = classes[cls]
    if member in members:
        return max(since, members[member])
    best = None
    for s in supers:
        v = member_since(classes, s, member, seen)
        if v is not None and (best is None or v < best):
            best = v
    return best


REF = re.compile(r'// (Method|InterfaceMethod|Field) ([\w/$]+)\.("?[\w<>$]+"?):(\S+)')
CLS = re.compile(r'// class ([\w/$]+)')


def main():
    api, root, limit = sys.argv[1], sys.argv[2], int(sys.argv[3])
    classes = load(api)
    files = [os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs if f.endswith('.class')]
    out = subprocess.run(['javap', '-c', '-p'] + files, capture_output=True, text=True, check=True).stdout
    bad = set()
    for line in out.splitlines():
        for m in REF.finditer(line):
            kind, cls, name, desc = m.groups()
            name = name.strip('"')
            if cls not in classes:
                continue
            key = name + desc if kind != 'Field' else name
            since = member_since(classes, cls, key)
            if since is None and kind != 'Field':
                # constructors / overloads not listed: fall back to class level
                since = classes[cls][0]
            if since is not None and since > limit:
                bad.add('%s.%s (API %d)' % (cls, key, since))
        for m in CLS.finditer(line):
            c = m.group(1)
            if c in classes and classes[c][0] > limit:
                bad.add('%s (API %d)' % (c, classes[c][0]))
    if bad:
        sys.exit('APIs newer than %d:\n  ' % limit + '\n  '.join(sorted(bad)))
    print('  all API references are available in API %d' % limit)


if __name__ == '__main__':
    main()
