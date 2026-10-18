# -*- coding: utf-8 -*-
"""Sonda v24.14o - uso de Ja/stream/token en el bundle."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()

# Ja y su uso
for m in re.finditer(r'Ja\b', js):
    ctx = js[max(0, m.start()-200):m.start()+260].replace('\n', ' ')
    print('--- Ja ctx:', ctx[:450])
    print()

# 'stream' + token combos
for kw in ['stream/${', 'streamUrl', 'stream_token', 'streamToken', 'token=']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:6]
    print(f'==== [{kw}] {len(hits)} hits')
    for ix in hits:
        print('   ', js[max(0, ix-250):ix+250].replace('\n', ' ')[:480])
    print()

# sources / playback response fields: buscar 'playback' mapping
for kw in ['sources:', 'sources:', 'mimeType', 'storageHost', 'file:', 'qualities', 'quality:']:
    hits = [m.start() for m in re.finditer(re.escape(kw), js)][:4]
    for ix in hits:
        print(f'[{kw}]', js[max(0, ix-150):ix+260].replace('\n', ' ')[:400])
        print()
