# -*- coding: utf-8 -*-
"""Sonda v24.14w - flujo completo + decrypt AES-256-GCM (pure python) + validar streams."""
import http.cookiejar, urllib.request, gzip, io, sys, time, json, uuid
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
def solve(nonce, difficulty, max_secs=420):
    t0 = time.time(); s = 0
    pre = (nonce + ':').encode()
    while True:
        if lz_bits(gr(pre + str(s).encode())) >= difficulty:
            return str(s), time.time() - t0
        s += 1
        if time.time() - t0 > max_secs: return None, time.time() - t0

# ===== AES-256 + GCM (pure python) =====
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
INV_SBOX = [0]*256
for i, v in enumerate(SBOX): INV_SBOX[v] = i
RCON = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36,0x6c,0xd8]

def xtime(a):
    a <<= 1
    if a & 0x100: a ^= 0x11b
    return a

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

def aes_decrypt_block(block, w, nr):
    # state column-major
    s = [[block[r + 4*c] for c in range(4)] for r in range(4)]
    def add_rk(rd):
        for c in range(4):
            for r in range(4):
                s[r][c] ^= w[rd][4*c + r]  # OJO: w words are big-endian column bytes
    # Nota: AES key schedule words: word j = bytes w[4j..4j+3], column c of state uses word rd*4+c -> bytes [4c..4c+3]
    def inv_shift_rows():
        for r in range(1, 4):
            s[r] = s[r][-r:] + s[r][:-r]
    def inv_sub_bytes():
        for r in range(4):
            for c in range(4):
                s[r][c] = INV_SBOX[s[r][c]]
    def inv_mix_columns():
        for c in range(4):
            a = [s[r][c] for r in range(4)]
            s[0][c] = gmul(a[0],14)^gmul(a[1],11)^gmul(a[2],13)^gmul(a[3],9)
            s[1][c] = gmul(a[0],9)^gmul(a[1],14)^gmul(a[2],11)^gmul(a[3],13)
            s[2][c] = gmul(a[0],13)^gmul(a[1],9)^gmul(a[2],14)^gmul(a[3],11)
            s[3][c] = gmul(a[0],11)^gmul(a[1],13)^gmul(a[2],9)^gmul(a[3],14)
    add_rk(nr)
    for rd in range(nr-1, 0, -1):
        inv_shift_rows()
        inv_sub_bytes()
        add_rk(rd)
        inv_mix_columns()
    inv_shift_rows()
    inv_sub_bytes()
    add_rk(0)
    return bytes(s[r][c] for c in range(4) for r in range(4))

def gmul(a, b):
    p = 0
    for _ in range(8):
        if b & 1: p ^= a
        hi = a & 0x80
        a = (a << 1) & 0xff
        if hi: a ^= 0x1b
        b >>= 1
    return p

def aes_ecb_decrypt(key, data):
    w, nr = expand_key(key)
    out = b''
    for i in range(0, len(data), 16):
        out += aes_decrypt_block(data[i:i+16], w, nr)
    return out

def gcm_decrypt(key, iv, ct, tag):
    # H = E(K, 0^16)
    H = aes_ecb_decrypt(key, b'\x00'*16)
    def gf_mult(x, y):
        # GF(2^128) mult per NIST SP800-38D (bit order: MSB first)
        z = 0
        v = x
        R = 0xe1000000000000000000000000000000
        for i in range(127, -1, -1):
            if (y >> i) & 1:
                z ^= v
            if v & 1:
                v = (v >> 1) ^ R
            else:
                v >>= 1
        return z
    def ghash(aad, ct):
        def blocks(d):
            for i in range(0, len(d), 16):
                b = d[i:i+16]
                if len(b) < 16: b += b'\x00'*(16-len(b))
                yield b
        y = 0
        for b in blocks(aad):
            y = gf_mult(y ^ int.from_bytes(b, 'big'), int.from_bytes(H, 'big'))
        for b in blocks(ct):
            y = gf_mult(y ^ int.from_bytes(b, 'big'), int.from_bytes(H, 'big'))
        lens = ((len(aad)*8) << 64) | (len(ct)*8)
        y = gf_mult(y ^ lens, int.from_bytes(H, 'big'))
        return y.to_bytes(16, 'big')
    # J0: iv 12 bytes -> J0 = iv || 0x00000001
    j0 = iv + b'\x00\x00\x00\x01'
    # inc32 counter CTR starting at J0+1
    def ctr_xor(counter0, data):
        out = bytearray()
        ctr = int.from_bytes(counter0, 'big')
        for i in range(0, len(data), 16):
            blk = ctr.to_bytes(16, 'big')
            ks = aes_ecb_decrypt(key, blk)
            chunk = data[i:i+16]
            out += bytes(a ^ b for a, b in zip(chunk, ks))
            ctr = (ctr + 1) & ((1 << 128) - 1)
        return bytes(out)
    pt = ctr_xor(j0, ct)
    # tag check: GHASH(aad='', ct) xor E(K,J0)
    s_ = ghash(b'', ct)
    ekj0 = aes_ecb_decrypt(key, j0)
    calc_tag = bytes(a ^ b for a, b in zip(s_, ekj0))
    if calc_tag != tag:
        raise ValueError(f'tag mismatch calc={calc_tag.hex()} vs {tag.hex()}')
    return pt

def b64url_dec(s):
    import base64
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
for CODE in ['rgcgmsu5j63y', '0nn2ohmw6jyv']:
    print('=' * 66)
    print('CODE', CODE)
    st, b = req(f'{B}/api/videos/{CODE}/embed/captcha', method='POST', body='{}')
    if st != 200: print('captcha fail', st, b[:150]); continue
    ch = json.loads(b)
    sol, dt = solve(ch['pow_nonce'], ch['pow_difficulty'])
    print(f'  solve: {sol} ({dt:.0f}s)')
    if sol is None: continue
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
    print('  playback keys:', {k: (v[:40] if isinstance(v, str) else v if not isinstance(v, list) else f'[{len(v)} parts]') for k, v in enc.items()})
    try:
        version = str(enc.get('version', ''))
        kp = enc.get('key_parts') or []
        pair = (int(version), 31 - int(version)) if version.isdigit() else None
        sel = []
        if pair:
            for idx in pair:
                if 1 <= idx <= len(kp): sel.append(kp[idx-1])
        if not sel: sel = kp
        key = b''.join(b64url_dec(s) for s in sel if s)
        iv = b64url_dec(enc['iv'])
        payload = b64url_dec(enc['payload'])
        tag = payload[-16:]
        ct = payload[:-16]
        pt = gcm_decrypt(key, iv, ct, tag)
        dec = json.loads(pt)
        print('  DECRYPTED sources:')
        for s in dec.get('sources', []):
            print('   ', s.get('quality'), s.get('height'), s.get('mime_type'), '|', (s.get('url') or '')[:100])
        for t2 in dec.get('tracks', [])[:3]:
            print('   track:', (t2.get('url') or '')[:80])
        # validar primer stream
        for s in dec.get('sources', []):
            u = s.get('url') or ''
            if not u: continue
            try:
                rq = urllib.request.Request(u, headers={'User-Agent': UA, 'Origin': 'https://bysekoze.com',
                                                        'Referer': 'https://bysekoze.com/', 'Range': 'bytes=0-63'})
                r = opener.open(rq, timeout=30)
                print('   VALIDATE', r.status, r.headers.get('Content-Type'), '|', u[:90])
            except Exception as e:
                print('   VALIDATE ERR', type(e).__name__, str(e)[:60], '|', u[:90])
            break
    except Exception as e:
        import traceback
        print('  decrypt ERR:', type(e).__name__, e)
    time.sleep(3)
