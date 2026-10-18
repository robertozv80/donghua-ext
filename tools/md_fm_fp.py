# -*- coding: utf-8 -*-
"""Sonda v24.14t - attestation vr/fr en pow bundle: endpoints."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js2 = open('tools/md_pow-DEJGtdh2.js', 'r', encoding='utf-8').read()
js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()

# URLs / endpoints en pow bundle
urls = sorted(set(re.findall(r'["\'](/[a-zA-Z0-9/_${}.-]{3,60})["\']', js2)))
print('pow paths:', urls)
apis = sorted(set(re.findall(r'`[^`]{0,120}`', js2)))
print('pow templates:')
for a in apis[:20]: print('   ', a[:120])

# fr y funciones relacionadas
for kw in ['fr=', 'vr=', 'Jn=', 'ur=', 'Nn=', 'ar=', 'Cn=', 'challenge', 'attest', 'viewer_id']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js2)][:3]
    print(f'==== [{kw}] {len(hits)}')
    for ix in hits:
        print('   ', js2[max(0, ix-150):ix+420].replace('\n', ' ')[:560])
    print()
