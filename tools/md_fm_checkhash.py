# -*- coding: utf-8 -*-
"""Valida gr() python vs JS (test:0 -> 2c62ed77...) y mide velocidad."""
import sys, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, 'tools')
from md_fm_flow import gr, wr

js_test0 = '2c62ed779d20d796ca039154fec1dbe30e3bef566b89a146d2e365a5ae4a98fb'
h = gr(b'test:0')
hexs = ''.join(f'{w:08x}' for w in h)
print('python gr(test:0):', hexs)
print('js     gr(test:0):', js_test0)
print('MATCH:', hexs == js_test0)

t0 = time.time()
n = 0
for s in range(0, 512):
    gr(b'test:' + str(s).encode())
    n += 1
dt = time.time() - t0
print(f'velocidad: {n/dt:.0f} hashes/s ({dt/n*1000:.2f} ms/hash)')
print('tiempo esperado diff=16 (65536 tries):', f'{65536/(n/dt):.0f}s')
