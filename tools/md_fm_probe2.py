# -*- coding: utf-8 -*-
"""Sonda v24.14b - MundoDonghua 'fm'(fmoon): unpack correcto (base 36/62)
y prueba de extraccion real del embed.
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, sys, time, string
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=40, extra=None):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    if refs: h['Referer'] = refs
    if extra: h.update(extra)
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

ALPH = string.digits + string.ascii_lowercase  # 0-36

def token_str(i, base):
    if i == 0: return '0'
    s = ''
    while i:
        c = i % base
        ch = chr(c + 29) if c > 35 else ALPH[c]
        s = ch + s
        i //= base
    return s

def js_unpack(packed):
    m = re.search(r"\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)", packed, re.S)
    if not m: return None
    p, a, k = m.group(1), int(m.group(2)), m.group(4).split('|')
    for c in range(len(k) - 1, -1, -1):
        if not k[c]: continue
        tok = token_str(c, a)
        p = re.sub(r'\b' + re.escape(tok) + r'\b', k[c].replace('\\', '\\\\').replace('/', '/'), p)
    return p

def iframes_from(un):
    return re.findall(r"<iframe[^>]*>", un)

results = {}
for ep in ['https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art/1',
           'https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art-2/1']:
    st, html = retry(get, ep)
    print('=' * 70)
    print('EP:', ep.rsplit('/', 2)[-2], '| HTTP', st)
    if st != 200: print(html[:200]); continue
    for i, script in enumerate(re.findall(r'<script[^>]*>(.*?)</script>', html, re.S)):
        if 'eval(function(p,a,c,k,e' not in script: continue
        for pm in re.finditer(r'eval\(function\(p,a,c,k,e,[^\r\n]+', script):
            un = js_unpack(pm.group(0))
            if not un: continue
            for ifr in iframes_from(un):
                print(f'   [script {i}] IFRAME: {ifr}')
    time.sleep(2)

print()
print('=== pruebo extraccion del embed fmoon ===')
# se vuelve a sacar el embed del segundo ep (fmoon es tab activo alli)
st, html = retry(get, 'https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art-2/1')
fmoon_url = None
for script in re.findall(r'<script[^>]*>(.*?)</script>', html, re.S):
    if 'eval(function(p,a,c,k,e' not in script: continue
    for pm in re.finditer(r'eval\(function\(p,a,c,k,e,[^\r\n]+', script):
        un = js_unpack(pm.group(0)) or ''
        if 'fmoon' not in un: continue
        m = re.search(r"iframe[^>]*src=\\?'(https?://[^'\\]+)", un)
        if m: fmoon_url = m.group(1)
print('fmoon embed URL:', fmoon_url)
if fmoon_url:
    # extraer ID y probar /e/ y /v/
    mid = re.search(r'/([ev])/([a-zA-Z0-9]+)', fmoon_url)
    print('parts:', mid.groups() if mid else None)
    st2, body = retry(get, fmoon_url, refs='https://www.mundodonghua.com/')
    print('embed HTTP', st2, 'len', len(body))
    if st2 == 200:
        # packed en la pagina del embed
        found_m3u8 = []
        for script in re.findall(r'<script[^>]*>(.*?)</script>', body, re.S):
            if 'eval(function(p,a,c,k,e' in script:
                for pm in re.finditer(r'eval\(function\(p,a,c,k,e,[^\r\n]+', script):
                    un = js_unpack(pm.group(0)) or ''
                    for mm in re.finditer(r'https?://[^\s"\'<>]+', un):
                        u = mm.group(0)
                        if '.m3u8' in u or '.mp4' in u: found_m3u8.append(u)
            else:
                for mm in re.finditer(r'https?://[^\s"\'<>]+', script):
                    u = mm.group(0)
                    if '.m3u8' in u or '.mp4' in u: found_m3u8.append(u)
        print('urls directas en embed:', list(dict.fromkeys(found_m3u8))[:8])
        # y con getAndUnpack estilar el mismo flujo que StreamWishExtractor:
        # buscar "file"/"hls" en el unpack
        for script in re.findall(r'<script[^>]*>(.*?)</script>', body, re.S):
            if 'eval(function(p,a,c,k,e' in script:
                for pm in re.finditer(r'eval\(function\(p,a,c,k,e,[^\r\n]+', script):
                    un = js_unpack(pm.group(0)) or ''
                    if 'file' in un or 'hls' in un or 'jw' in un.lower():
                        print('--- unpack con file/hls/jw (len', len(un), '):')
                        print(un[:600])
