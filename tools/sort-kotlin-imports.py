#!/usr/bin/env python3
"""Sorts each Kotlin file's import block in ktlint's default order (IntelliJ layout):
everything else, then java.*, javax.*, kotlin.*, then aliases. Usage: tools/sort-kotlin-imports.py [--fix] [files...] (default: every .kt file under app/src).
Until ktlint is adopted, this keeps import blocks in the order it would use."""
import sys, re
fix = '--fix' in sys.argv
import glob
files = [a for a in sys.argv[1:] if a != '--fix'] or sorted(glob.glob('app/src/**/*.kt', recursive=True))
def key(line):
    name = line[len('import '):].strip()
    alias = ' as ' in name
    group = 4 if alias else 1 if name.startswith('java.') else 2 if name.startswith('javax.') else 3 if name.startswith('kotlin.') else 0
    return (group, name.replace('`', ''))
bad = []
for p in files:
    lines = open(p).read().split('\n')
    idx = [i for i, l in enumerate(lines) if l.startswith('import ')]
    if not idx: continue
    first, last = idx[0], idx[-1]
    block = lines[first:last + 1]
    if any(l and not l.startswith('import ') for l in block): continue  # comments inside: leave alone
    imports = [l for l in block if l]
    want = sorted(dict.fromkeys(imports), key=key)
    if block != want:
        bad.append(p)
        if fix:
            lines[first:last + 1] = want
            open(p, 'w').write('\n'.join(lines))
print(len(bad), 'files', 'fixed' if fix else 'unsorted')
for p in bad: print(' ', p)
sys.exit(1 if bad and not fix else 0)
