# -*- coding: utf-8 -*-
"""Sonda v24.14m - solver PoW: export{s} del pow bundle + nt()/Te() builder."""
import re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

js2 = open('tools/md_pow-DEJGtdh2.js', 'r', encoding='utf-8').read()
js = open('tools/md_videoPagesBundle-Bgi0QmPo.js', 'r', encoding='utf-8').read()

# 1) export al final del pow bundle
m = re.search(r'export\{([^}]+)\}', js2)
print('pow exports:', m.group(1) if m else 'NO EXPORT (module bundler form)')
# buscar el export en formato bundler: ...as s,...as a,...as e
for mm in re.finditer(r'\}\s*as\s*(s|a|e)\b', js2[-3000:]):
    print('tail export ctx:', js2[-3000:][max(0, mm.start()-200):mm.end()+50].replace('\n', ' '))
# tail completo
print('--- TAIL 1500:')
print(js2[-1500:])
print()

# 2) funciones con loop while + hash en pow bundle (el solver)
for m2 in re.finditer(r'function\s+\w+\s*\(', js2):
    pass
# buscar patrones de hashcash: iterations, hashHex, leading zeros
for kw in ['while(', 'iterations', 'hex', 'toString(16)', 'counter']:
    hits = [m3.start() for m3 in re.finditer(re.escape(kw), js2)][:4]
    print(f'[{kw}] hits: {len(hits)}')
    for ix in hits:
        print('   ', js2[max(0, ix-100):ix+200].replace('\n', ' ')[:280])
print()

# 3) nt y Te en videoPages
for kw in ['nt=', 'Te=', 'Zt=', 'captcha']:
    hits = [m3.start() for m3 in re.finditer(re.escape(kw), js)][:3]
    for ix in hits:
        print(f'--- [{kw}] ...{js[max(0,ix-60):ix+260]}...'.replace('\n', ' '))
