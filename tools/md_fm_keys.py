# -*- coding: utf-8 -*-
"""Sonda v24.14q - ws/ks/key_parts en el bundle."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()

for kw in ['ws=', 'ks=', 'key_parts', 'rr=', 'ws(', 'ks(', 'keyParts']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:8]
    print(f'==== [{kw}] {len(hits)} hits')
    for ix in hits:
        print('   ', js[max(0, ix-280):ix+320].replace('\n', ' ')[:560])
        print()
