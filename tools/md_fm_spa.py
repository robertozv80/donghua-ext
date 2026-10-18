# -*- coding: utf-8 -*-
"""Sonda v24.14d - SPA de bysekoze: bajar bundle y buscar endpoints."""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, sys, time, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=60, extra=None):
    h = {'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5'}
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

st, js = retry(get, 'https://bysekoze.com/assets/index-DocunfmE.js', refs='https://bysekoze.com/e/rgcgmsu5j63y')
print('bundle HTTP', st, 'len', len(js))
if st == 200:
    open('tools/md_bysekoze_bundle.js', 'w', encoding='utf-8').write(js)
    # buscar rutas API
    apis = sorted(set(re.findall(r'["\'](/(?:api|v\d|video|embed|source|stream|file|player)[^"\']{0,60})["\']', js)))
    print('API paths:', apis)
    urls = sorted(set(re.findall(r'https?://[a-zA-Z0-9.-]+\.[a-z]{2,}[^"\'\s]*', js)))
    print('URLs absolutas (primeras 30):')
    for u in urls[:30]: print('  ', u[:110])
    # fetch/axios calls
    fetches = re.findall(r'fetch\(([^)]{0,120})\)', js)
    print('fetch calls:', fetches[:10])
    # buscar 'slug' / 'hash' / 'id' patterns
    for kw in ['sources', 'source', 'm3u8', 'mp4', 'hls', 'playlist', 'file']:
        idxs = [m.start() for m in re.finditer(kw, js)][:5]
        for ix in idxs:
            print(f'--- {kw} @{ix}: ...{js[max(0,ix-80):ix+160]}...')
