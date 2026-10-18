# -*- coding: utf-8 -*-
"""Valida gr() python vs JS y mide velocidad (standalone)."""
import sys, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

M32 = 0xFFFFFFFF
def rotl(x, n): return ((x << n) | (x >> (32 - n))) & M32
def imul(a, b): return (a * b) & M32

def ye(t):
    t[0] = (t[0] + t[1]) & M32; t[3] = rotl(t[3] ^ t[0], 16)
    t[2] = (t[2] + t[3]) & M32; t[1] = rotl(t[1] ^ t[2], 12)
    t[0] = (t[0] + t[1]) & M32; t[3] = rotl(t[3] ^ t[0], 8)
    t[2] = (t[2] + t[3]) & M32; t[1] = rotl(t[1] ^ t[2], 7)

def gr(data):
    e = [1779033703, 3144134277, 1013904242, 2773480762]
    for b in data:
        e[0] = (e[0] + b) & M32
        e[0] = rotl(e[0], 7)
        ye(e)
    for _ in range(8): ye(e)
    be = 512; lt = be - 1; dr = 2; lr = 2654435761; hr = 2246822519
    r = [0] * be
    for i in range(be):
        ye(e)
        r[i] = (e[0] ^ e[2]) & M32
    for _ in range(dr):
        for s in range(be):
            a = r[s] & lt
            c = (r[s] + r[a]) & M32
            c = rotl(c, 13)
            c = (c ^ imul(r[(s + 1) & lt], lr)) & M32
            r[s] = c
            e[0] = (e[0] ^ c) & M32
            ye(e)
    n = [0] * 8; o = be // 8
    for i in range(8):
        ye(e)
        s2 = e[0]
        a2 = i * o
        for c2 in range(o):
            d = r[a2 + c2]
            s2 = (s2 + d) & M32
            s2 = rotl(s2, 5)
            s2 = (s2 ^ imul(d, hr)) & M32
        n[i] = (s2 ^ e[2]) & M32
    return n

js_test0 = '2c62ed779d20d796ca039154fec1dbe30e3bef566b89a146d2e365a5ae4a98fb'
h = gr(b'test:0')
hexs = ''.join(f'{w:08x}' for w in h)
print('python gr(test:0):', hexs)
print('js     gr(test:0):', js_test0)
print('MATCH:', hexs == js_test0)

t0 = time.time()
n = 0
for s in range(0, 300):
    gr(b'test:' + str(s).encode())
    n += 1
dt = time.time() - t0
print(f'velocidad: {n/dt:.1f} hashes/s ({dt/n*1000:.2f} ms/hash)')
print('tiempo esperado diff=16:', f'{65536/(n/dt):.0f}s')
