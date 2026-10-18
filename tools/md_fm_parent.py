# -*- coding: utf-8 -*-
"""Sonda v24.14u - embedParentHost y postMessage handshake."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()
js2 = open('tools/md_pow-DEJGtdh2.js', 'r', encoding='utf-8').read()

for kw in ['embedParentHost', 'embedParentReferrer', 'embedParentFrame', 'postMessage', 'ancestorOrigins', 'message']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:5]
    print(f'==== [{kw}] {len(hits)}')
    for ix in hits:
        print('   ', js[max(0, ix-250):ix+300].replace('\n', ' ')[:520])
    print()

# sr / cr (client/storage)
for kw in ['sr=', 'cr=', 'client:', 'storage:']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js2)][:4]
    print(f'==== pow [{kw}] {len(hits)}')
    for ix in hits:
        print('   ', js2[max(0, ix-200):ix+400].replace('\n', ' ')[:560])
    print()

# pow Xt (base url)
for m in re.finditer(r'\bXt=', js2):
    print('pow Xt def:', js2[m.start()-30:m.start()+280].replace('\n', ' ')[:300])
