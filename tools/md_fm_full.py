# -*- coding: utf-8 -*-
"""Sonda v24.14v - flujo completo: captcha->solve->verify->playback (y attestation si hace falta)."""
import http.cookiejar, urllib.request, gzip, io, sys, time, json, uuid, secrets
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
    be = 512; lt = be - 1; lr = 2654435761; hr = 2246822519
    r = [0] * be
    for i in range(be):
        ye(e)
        r[i] = (e[0] ^ e[2]) & M32
    for _ in range(2):
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

def lz_bits(words):
    tot = 0
    for w in words:
        if w == 0:
            tot += 32; continue
        tot += 32 - w.bit_length()
        break
    return tot

def solve(nonce, difficulty, max_secs=420):
    t0 = time.time()
    s = 0
    pre = (nonce + ':').encode()
    while True:
        if lz_bits(gr(pre + str(s).encode())) >= difficulty:
            return str(s), time.time() - t0
        s += 1
        if time.time() - t0 > max_secs:
            return None, time.time() - t0

# ===== HTTP =====
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
MD = 'https://www.mundodonghua.com/'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def req(url, method='GET', body=None, captcha=None, to=60):
    h = {'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Origin': 'https://www.mundodonghua.com', 'Referer': MD,
         'X-Embed-Origin': 'https://www.mundodonghua.com',
         'X-Embed-Referer': MD,
         'X-Embed-Parent': MD,
         'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'cross-site'}
    if captcha: h['X-Captcha-Token'] = captcha
    data = body.encode() if isinstance(body, str) else body
    if data is not None: h['Content-Type'] = 'application/json'
    try:
        r = opener.open(urllib.request.Request(url, data=data, headers=h, method=method), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

B = 'https://bysekoze.com'
CODE = 'rgcgmsu5j63y'

st, b = req(f'{B}/api/videos/{CODE}/embed/details')
print('1) details:', st, b[:120])

st, b = req(f'{B}/api/videos/{CODE}/embed/captcha', method='POST', body='{}')
print('2) captcha:', st, b[:120])
ch = json.loads(b)

print('3) solve (hasta 7 min)...')
sol, dt = solve(ch['pow_nonce'], ch['pow_difficulty'])
print(f'   solucion={sol} ({dt:.0f}s)')
if sol is None: sys.exit(1)

st, b = req(f'{B}/api/videos/{CODE}/embed/captcha/verify', method='POST',
            body=json.dumps({'pow_token': ch['pow_token'], 'solution': sol}))
print('4) verify:', st, b[:120])
v = json.loads(b)
if v.get('status') != 'ok':
    print('   verify fallo:', v); sys.exit(1)
tok = v['token']

st, b = req(f'{B}/api/videos/{CODE}/embed/playback', captcha=tok)
print('5) playback GET:', st, b[:400])
if st != 200:
    st2, b2 = req(f'{B}/api/videos/{CODE}/embed/playback', method='POST', body='{}', captcha=tok)
    print('   playback POST {}:', st2, b2[:200])
    # con fingerprint falso por si el server solo exige presencia
    fp = {'token': tok, 'viewer_id': str(uuid.uuid4()), 'device_id': str(uuid.uuid4()), 'confidence': 0.9}
    st3, b3 = req(f'{B}/api/videos/{CODE}/embed/playback', method='POST',
                  body=json.dumps({'fingerprint': fp}), captcha=tok)
    print('   playback POST fp:', st3, b3[:400])
