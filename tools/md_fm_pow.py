# -*- coding: utf-8 -*-
"""Sonda v24.14l - extraer flujo PoW del bundle videoPagesBundle + pow."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()
js2 = open('tools/md_pow-DEJGtdh2.js', 'r', encoding='utf-8').read()

def ctx(s, kw, n=5, before=150, after=450):
    out = []
    for m in list(re.finditer(re.escape(kw), s))[:n]:
        out.append(s[max(0, m.start() - before):m.start() + after].replace('\n', ' '))
    return out

print('########## videoPagesBundle ##########')
for kw in ['pow_nonce', 'access', 'X-Captcha-Token', 'X-Embed-Origin', 'X-Embed-Parent',
           'X-Embed-Referer', 'captcha/gate', 'solve', 'difficulty', 'sha256', 'crypto.subtle',
           'api/videos/access']:
    for c in ctx(js, kw, n=3):
        print(f'--- [{kw}]:')
        print('   ', c[:600])
    print()

print('########## pow bundle (pow-DEJGtdh2.js) ##########')
for kw in ['exports', 'pow_nonce', 'sha256', 'digest', 'leading', 'difficulty', 'nonce']:
    for c in ctx(js2, kw, n=2, before=120, after=350):
        print(f'--- [{kw}]:')
        print('   ', c[:450])
    print()
