# -*- coding: utf-8 -*-
"""Sonda v24.14j - unpack completo de los scripts del episodio SA2 ep1."""
import http.cookiejar, urllib.request, gzip, io, sys, time, re, string
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=40):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    if refs: h['Referer'] = refs
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

def retry(fn, *a, **k):
    st, b = -1, ''
    for i in range(3):
        st, b = fn(*a, **k)
        if st == 200: return st, b
        time.sleep(2.5 * (i + 1))
    return st, b

ALPH = string.digits + string.ascii_lowercase

def token_str(i, base):
    if i == 0: return '0'
    s = ''
    while i:
        c = i % base
        s = (chr(c + 29) if c > 35 else ALPH[c]) + s
        i //= base
    return s

def js_unpack(packed):
    m = re.search(r"\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)", packed, re.S)
    if not m: return None
    p, a, k = m.group(1), int(m.group(2)), m.group(4).split('|')
    for c in range(len(k) - 1, -1, -1):
        if not k[c]: continue
        p = re.sub(r'\b' + re.escape(token_str(c, a)) + r'\b', k[c].replace('\\', '\\\\'), p)
    return p

st, html = retry(get, 'https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art-2/1')
print('HTTP', st)
n = 0
for script in re.findall(r'<script[^>]*>(.*?)</script>', html, re.S):
    if 'eval(function(p,a,c,k,e' not in script: continue
    for pm in re.finditer(r'eval\(function\(p,a,c,k,e,[^\r\n]+', script):
        un = js_unpack(pm.group(0))
        if not un: continue
        n += 1
        print(f'===== SCRIPT PACKED #{n} (unpacked {len(un)} chars) =====')
        print(un)
        print()
