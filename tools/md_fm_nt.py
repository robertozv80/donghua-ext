# -*- coding: utf-8 -*-
"""Sonda v24.14r - nt completo + call sites de Et / playback."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()

# definicion nt
for m in re.finditer(r'\bnt=\(?', js):
    print('--- nt def:', js[m.start()-40:m.start()+300].replace('\n', ' ')[:340])
    print()

# playback call sites
for m in re.finditer(r'"playback"', js):
    print('--- "playback":', js[max(0, m.start()-260):m.start()+200].replace('\n', ' ')[:460])
    print()

# Et call sites
for m in re.finditer(r'\bEt\(', js):
    print('--- Et(:', js[max(0, m.start()-300):m.start()+150].replace('\n', ' ')[:450])
    print()

# mode strings
for kw in ['mode:"embed"', '"embed"', 'isEmbed', 'embed:']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:6]
    print(f'==== [{kw}] {len(hits)}')
    for ix in hits:
        print('   ', js[max(0, ix-150):ix+200].replace('\n', ' ')[:330])
    print()
