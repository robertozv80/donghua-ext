# -*- coding: utf-8 -*-
"""Sonda v24.14s - Zt() base URL + fingerprint resolver."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()

for kw in ['Zt=', 'Zt(', 'API_BASE', 'apiBase', 'VITE_', 'location.origin']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:6]
    print(f'==== [{kw}] {len(hits)}')
    for ix in hits:
        print('   ', js[max(0, ix-200):ix+280].replace('\n', ' ')[:460])
    print()

# resolveFingerprint / ue / Qe
for kw in ['resolveFingerprint', 'ue=', 'Qe=', 'fingerprint:']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:5]
    print(f'==== [{kw}] {len(hits)}')
    for ix in hits:
        print('   ', js[max(0, ix-200):ix+300].replace('\n', ' ')[:480])
    print()
