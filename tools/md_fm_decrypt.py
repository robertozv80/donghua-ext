# -*- coding: utf-8 -*-
"""Sonda v24.14p - logica de decrypt de playback en el bundle."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()

for kw in ['decrypt', 'Aa=', 'Xt=', 'Xt(', 'atob', 'SubtleCrypto', 'AES', 'GCM', 'crypto']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:6]
    print(f'==== [{kw}] {len(hits)} hits')
    for ix in hits:
        print('   ', js[max(0, ix-300):ix+350].replace('\n', ' ')[:600])
        print()
