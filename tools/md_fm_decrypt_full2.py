# -*- coding: utf-8 -*-
"""Sonda v24.14x - AES forward (CTR keystream) + decrypt GCM correcto."""
import http.cookiejar, urllib.request, gzip, io, sys, time, json, uuid, base64
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
        ye(e); r[i] = (e[0] ^ e[2]) & M32
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
def solve(nonce, difficulty, max_secs=300):
    t0 = time.time(); s = 0
    pre = (nonce + ':').encode()
    while True:
        if lz_bits(gr(pre + str(s).encode())) >= difficulty:
            return str(s)
        s += 1
        if time.time() - t0 > max_secs: return None

# ===== AES-256 FORWARD (para CTR keystream y H) =====
SBOX = [0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
        0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
        0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
        0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
        0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
        0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
        0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
        0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
        0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
        0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
        0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
        0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
        0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
        0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
        0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
        0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16]
RCON = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36,0x6c,0xd8]

def gmul(a, b):
    p = 0
    for _ in range(8):
        if b & 1: p ^= a
        hi = a & 0x80
        a = (a << 1) & 0xff
        if hi: a ^= 0x1b
        b >>= 1
    return p

def expand_key(key):
    nk = len(key) // 4
    nr = nk + 6
    w = [list(key[4*i:4*i+4]) for i in range(nk)]
    for i in range(nk, 4*(nr+1)):
        t = list(w[i-1])
        if i % nk == 0:
            t = t[1:] + t[:1]
            t = [SBOX[b] for b in t]
            t[0] ^= RCON[i//nk - 1]
        elif nk > 6 and i % nk == 4:
            t = [SBOX[b] for b in t]
        w.append([w[i-nk][j] ^ t[j] for j in range(4)])
    return w, nr

def aes_encrypt_block(block, w, nr):
    s = [[block[r + 4*c] for c in range(4)] for r in range(4)]
    def add_rk(rd):
        for c in range(4):
            word = w[4*rd + c]
            for r in range(4):
                s[r][c] ^= word[r]
    def sub_shift():
        for r in range(4):
            for c in range(4):
                s[r][c] = SBOX[s[r][c]]
        for r in range(1, 4):
            s[r] = s[r][r:] + s[r][:r]
    def mix():
        for c in range(4):
            a = [s[r][c] for r in range(4)]
            s[0][c] = gmul(a[0],2)^gmul(a[1],3)^a[2]^a[3]
            s[1][c] = a[0]^gmul(a[1],2)^gmul(a[2],3)^a[3]
            s[2][c] = a[0]^a[1]^gmul(a[2],2)^gmul(a[3],3)
            s[3][c] = gmul(a[0],3)^a[1]^a[2]^gmul(a[3],2)
    add_rk(0)
    for rd in range(1, nr):
        sub_shift()
        mix()
        add_rk(rd)
    sub_shift()
    add_rk(nr)
    return bytes(s[r][c] for c in range(4) for r in range(4))

def gcm_decrypt(key, iv, ct, tag, aad=b''):
    w, nr = expand_key(key)
    H = aes_encrypt_block(b'\x00'*16, w, nr)
    R = 0xe1000000000000000000000000000000
    def gf_mult(x, y):
        z = 0; v = x
        for i in range(127, -1, -1):
            if (y >> i) & 1: z ^= v
            v = (v >> 1) ^ R if v & 1 else v >> 1
        return z
    def ghash(data):
        y = 0
        for i in range(0, len(data), 16):
            b = data[i:i+16]
            if len(b) < 16: b += b'\x00'*(16-len(b))
            y = gf_mult(y ^ int.from_bytes(b, 'big'), int.from_bytes(H, 'big'))
        return y
    y = ghash(ct)  # aad vacio
    lens = ((len(aad)*8) << 64) | (len(ct)*8)
    y = gf_mult(y ^ lens, int.from_bytes(H, 'big'))
    j0 = iv + b'\x00\x00\x00\x01'
    ekj0 = aes_encrypt_block(j0, w, nr)
    calc_tag = bytes(a ^ b for a, b in zip(y.to_bytes(16, 'big'), ekj0))
    if calc_tag != tag:
        raise ValueError(f'tag mismatch calc={calc_tag.hex()} vs {tag.hex()}')
    # CTR desde J0+1
    pt = bytearray()
    ctr = int.from_bytes(j0, 'big') + 1
    for i in range(0, len(ct), 16):
        ks = aes_encrypt_block(ctr.to_bytes(16, 'big'), w, nr)
        chunk = ct[i:i+16]
        pt += bytes(a ^ b for a, b in zip(chunk, ks))
        ctr = (ctr + 1) & ((1 << 128) - 1)
    return bytes(pt)

def b64url_dec(s):
    pad = '=' * ((4 - len(s) % 4) % 4)
    return base64.urlsafe_b64decode(s + pad)

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
    h = {'User-Agent': UA, 'Accept': '*/*',
         'Origin': 'https://www.mundodonghua.com', 'Referer': MD,
         'X-Embed-Origin': 'https://www.mundodonghua.com',
         'X-Embed-Referer': MD, 'X-Embed-Parent': MD,
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
CODE = sys.argv[1] if len(sys.argv) > 1 else 'rgcgmsu5j63y'
print('CODE', CODE)
ok = False
for attempt in range(2):
    st, b = req(f'{B}/api/videos/{CODE}/embed/captcha', method='POST', body='{}')
    if st != 200: print('captcha fail', st, b[:150]); break
    ch = json.loads(b)
    print(f'  intento {attempt+1}: solve diff={ch["pow_difficulty"]}...')
    sol = solve(ch['pow_nonce'], ch['pow_difficulty'])
    if sol is None:
        print('  solve timeout, reintento con captcha nuevo'); continue
    print('  sol:', sol)
    st, b = req(f'{B}/api/videos/{CODE}/embed/captcha/verify', method='POST',
                body=json.dumps({'pow_token': ch['pow_token'], 'solution': sol}))
    v = json.loads(b)
    if v.get('status') != 'ok': print('  verify fail', v); continue
    tok = v['token']
    fp = {'token': tok, 'viewer_id': str(uuid.uuid4()), 'device_id': str(uuid.uuid4()), 'confidence': 0.9}
    st, b = req(f'{B}/api/videos/{CODE}/embed/playback', method='POST',
                body=json.dumps({'fingerprint': fp}), captcha=tok)
    print('  playback:', st)
    if st != 200: print('  ', b[:200]); continue
    pb = json.loads(b)
    enc = pb.get('playback') or {}
    print('  keys:', {k: (v[:36] if isinstance(v, str) else f'[{len(v)}]' if isinstance(v, list) else v) for k, v in enc.items()})
    version = str(enc.get('version', ''))
    kp = enc.get('key_parts') or []
    sel = []
    if version.isdigit():
        for idx in (int(version), 31 - int(version)):
            if 1 <= idx <= len(kp): sel.append(kp[idx-1])
    if not sel: sel = kp
    key = b''.join(b64url_dec(s) for s in sel if s)
    print(f'  version={version} parts={len(kp)} sel={len(sel)} keylen={len(key)}')
    iv = b64url_dec(enc['iv'])
    payload = b64url_dec(enc['payload'])
    pt = gcm_decrypt(key, iv, payload[:-16], payload[-16:])
    dec = json.loads(pt)
    print('  ===== SOURCES DESCIFRADOS =====')
    print(json.dumps(dec, indent=1, ensure_ascii=False)[:2200])
    for s in dec.get('sources', []):
        u = s.get('url') or ''
        if not u: continue
        try:
            rq = urllib.request.Request(u, headers={'User-Agent': UA, 'Origin': 'https://bysekoze.com',
                                                    'Referer': 'https://bysekoze.com/', 'Range': 'bytes=0-63'})
            r = opener.open(rq, timeout=30)
            head = r.read(20)
            print('   VALIDATE', r.status, r.headers.get('Content-Type'), '| magic:', head[:12], '|', u[:90])
        except Exception as e:
            print('   VALIDATE ERR', type(e).__name__, str(e)[:60], '|', u[:90])
        break
    ok = True
    break
if not ok: print('SIN EXITO')
