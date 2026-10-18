# -*- coding: utf-8 -*-
"""Sonda v24.14n - flujo completo bysekoze API con PoW en Python."""
import http.cookiejar, urllib.request, gzip, io, sys, time, json, re, struct
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

try:
    import numpy as np
    HAS_NUMPY = True
except Exception:
    HAS_NUMPY = False
print('numpy:', HAS_NUMPY)

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
MD = 'https://www.mundodonghua.com/'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def req(url, method='GET', body=None, headers=None, to=40):
    h = {'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Origin': 'https://www.mundodonghua.com', 'Referer': MD,
         'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'cross-site'}
    if headers: h.update(headers)
    data = body.encode() if isinstance(body, str) else body
    if data is not None and 'Content-Type' not in h: h['Content-Type'] = 'application/json'
    try:
        r = opener.open(urllib.request.Request(url, data=data, headers=h, method=method), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

# ===== solver PoW (port de gr/wr/yr/Er del bundle) =====
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

def wr(words):
    e = 0
    for w in words:
        if w == 0:
            e += 32; continue
        return e + (32 - w.bit_length())
    return e

def solve(nonce, difficulty, timeout=60.0):
    if difficulty <= 0: return '0'
    t0 = time.time()
    s = 0
    while True:
        if wr(gr((nonce + ':' + str(s)).encode('utf-8'))) >= difficulty:
            return str(s)
        s += 1
        if s % 512 == 0 and time.time() - t0 > timeout:
            return None

def solve_np(nonce, difficulty, timeout=120.0):
    """Solver vectorizado con numpy: mismo gr() sobre batch de counters."""
    if not HAS_NUMPY: return solve(nonce, difficulty, timeout)
    t0 = time.time()
    base = nonce + ':'
    be, lt, dr, lr, hr = 512, 511, 2, 2654435761, 2246822519
    BATCH = 200_000
    start = 0
    while True:
        counters = np.arange(start, start + BATCH, dtype=np.uint32)
        strs = [base + str(c) for c in counters]
        L = max(len(x) for x in strs)
        bmat = np.zeros((len(strs), L), dtype=np.uint32)
        for i, x in enumerate(strs):
            b = np.frombuffer(x.encode(), dtype=np.uint8).astype(np.uint32)
            bmat[i, :len(b)] = b
        # estado e[0..3] por elemento
        e0 = np.full(len(strs), 1779033703, dtype=np.uint64)
        e1 = np.full(len(strs), 3144134277, dtype=np.uint64)
        e2 = np.full(len(strs), 1013904242, dtype=np.uint64)
        e3 = np.full(len(strs), 2773480762, dtype=np.uint64)
        def ye_v():
            nonlocal e0, e1, e2, e3
            e0 = (e0 + e1) & M32; e3 = ((e3 ^ e0) << 16 | (e3 ^ e0) >> 16) & M32
            e2 = (e2 + e3) & M32; e1 = ((e1 ^ e2) << 12 | (e1 ^ e2) >> 20) & M32
            e0 = (e0 + e1) & M32; e3 = ((e3 ^ e0) << 8 | (e3 ^ e0) >> 24) & M32
            e2 = (e2 + e3) & M32; e1 = ((e1 ^ e2) << 7 | (e1 ^ e2) >> 25) & M32
        # loop de bytes
        for j in range(L):
            e0 = (e0 + bmat[:, j]) & M32
            e0 = ((e0 << 7) | (e0 >> 25)) & M32
            ye_v()
        for _ in range(8): ye_v()
        r = np.zeros((len(strs), be), dtype=np.uint64)
        for i in range(be):
            ye_v()
            r[:, i] = (e0 ^ e2) & M32
        for _ in range(dr):
            for s in range(be):
                a = r[:, s] & lt
                c = (r[:, s] + r[:, a]) & M32
                c = ((c << 13) | (c >> 19)) & M32
                c = (c ^ ((r[:, (s + 1) & lt] * lr) & M32)) & M32
                r[:, s] = c
                e0 = (e0 ^ c) & M32
                ye_v()
        n8 = np.zeros((len(strs), 8), dtype=np.uint64)
        o = be // 8
        for i in range(8):
            ye_v()
            s2 = e0.copy()
            a2 = i * o
            for c2 in range(o):
                d = r[:, a2 + c2]
                s2 = (s2 + d) & M32
                s2 = ((s2 << 5) | (s2 >> 27)) & M32
                s2 = (s2 ^ ((d * hr) & M32)) & M32
            n8[:, i] = (s2 ^ e2) & M32
        # leading zero bits por fila
        ok = np.zeros(len(strs), dtype=bool)
        rem = np.zeros(len(strs), dtype=np.int64)
        for col in range(8):
            w = n8[:, col]
            lz = np.where(w == 0, 32, 32 - np.ceil(np.log2(np.where(w == 0, 1, w).astype(np.float64))).astype(np.int64))
            # clz exacto via bit_length
            lz2 = np.array([32 - int(x).bit_length() if x else 32 for x in w[:0]]) if False else None
            done = (w != 0) & ~ok
            if done.any():
                bits = np.array([32 - int(v).bit_length() if v else 0 for v in w[done]], dtype=np.int64)
                rem[done] = rem[done] + bits  # no exacto, se corrige abajo
                ok |= done
        # calculo exacto fila por fila solo para los candidatos (primeros no-cero)
        cands = np.where(ok)[0]
        for i in cands:
            tot = 0
            for col in range(8):
                w = int(n8[i, col])
                if w == 0:
                    tot += 32; continue
                tot += 32 - w.bit_length()
                break
            if tot >= difficulty:
                return str(start + i)
        start += BATCH
        if time.time() - t0 > timeout:
            return None

CODE = 'rgcgmsu5j63y'
B = 'https://bysekoze.com'

print('1) details')
st, b = req(f'{B}/api/videos/{CODE}/embed/details')
print('   HTTP', st, b[:200])
try: det = json.loads(b)
except Exception: det = {}
print('   captcha_required:', det.get('captcha_required'), '| frame:', det.get('embed_frame_url'))

print('2) captcha start')
st, b = req(f'{B}/api/videos/{CODE}/embed/captcha', method='POST', body='{}')
print('   HTTP', st, b[:220])
try: ch = json.loads(b)
except Exception:
    ch = {}
if not ch:
    sys.exit('sin challenge')

nonce = ch.get('pow_nonce'); diff = ch.get('pow_difficulty'); tok = ch.get('pow_token')
print(f'   nonce={nonce} difficulty={diff} token={tok}')

print('3) solve')
t0 = time.time()
sol = solve(nonce, diff, timeout=90)
print(f'   solucion={sol} en {time.time()-t0:.1f}s')
if sol is None:
    print('   timeout solve; intento numpy'); sol = solve_np(nonce, diff)
    print('   solucion numpy:', sol)
if sol is None: sys.exit('no solution')

print('4) verify')
st, b = req(f'{B}/api/videos/{CODE}/embed/captcha/verify', method='POST',
            body=json.dumps({'pow_token': tok, 'solution': sol}))
print('   HTTP', st, b[:200])
try: v = json.loads(b)
except Exception: v = {}
cap_tok = v.get('token')
print('   status:', v.get('status'), '| captcha token:', (cap_tok or '')[:40])

print('5) playback')
st, b = req(f'{B}/api/videos/{CODE}/embed/playback',
            headers={'X-Captcha-Token': cap_tok or ''})
print('   HTTP', st)
try:
    pb = json.loads(b)
    print(json.dumps(pb, indent=1)[:3000])
except Exception:
    print('   body:', b[:400])
