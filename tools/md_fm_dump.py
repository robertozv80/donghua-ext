# -*- coding: utf-8 -*-
"""Sonda v24.14c - dump crudo del stub de bysekoze y ver a donde redirige."""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, sys, time
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
        return r.status, _read(r), r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace'), url
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}', url

def retry(fn, *a, **k):
    st, b, u = -1, '', ''
    for i in range(3):
        st, b, u = fn(*a, **k)
        if st == 200: return st, b, u
        time.sleep(2.5 * (i + 1))
    return st, b, u

st, body, final = retry(get, 'https://bysekoze.com/e/rgcgmsu5j63y', refs='https://www.mundodonghua.com/')
print('HTTP', st, '| len', len(body), '| final url', final)
print('-' * 60)
print(body)
