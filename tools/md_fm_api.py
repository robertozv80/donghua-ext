# -*- coding: utf-8 -*-
"""Sonda v24.14e - API bysekoze /api/videos/{id}/embed/details con distintos Referer."""
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

def api(url, refs=None, origin=None, extra=None, to=40):
    h = {'User-Agent': UA, 'Accept': '*/*',
         'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'same-origin'}
    if refs: h['Referer'] = refs
    if origin: h['Origin'] = origin
    if extra: h.update(extra)
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

VID = 'rgcgmsu5j63y'
tests = [
    ('sin referer', None, None, None),
    ('referer bysekoze /e/', 'https://bysekoze.com/e/' + VID, 'https://bysekoze.com', None),
    ('referer mundodonghua ficha', 'https://www.mundodonghua.com/', 'https://www.mundodonghua.com', None),
    ('referer mundodonghua /ver/', 'https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art-2/1', 'https://www.mundodonghua.com', None),
    ('referer google', 'https://www.google.com/', 'https://www.google.com', None),
    ('referer tiodonghua', 'https://tiodonghua.lat/', 'https://tiodonghua.lat', None),
    ('X-From-Embed mundodonghua', 'https://bysekoze.com/e/' + VID, 'https://bysekoze.com', {'X-Embed-Origin': 'mundodonghua'}),
]
for name, r, o, x in tests:
    st, b = api(f'https://bysekoze.com/api/videos/{VID}/embed/details', refs=r, origin=o, extra=x)
    print(f'[{name}] HTTP {st} | {b[:220]}')
    time.sleep(1)

print()
print('=== variaciones de ruta ===')
for path in [f'/api/videos/{VID}/embed/details', f'/api/videos/{VID}/details',
             f'/api/embed/{VID}', f'/api/video/{VID}', f'/api/videos/{VID}']:
    st, b = api('https://bysekoze.com' + path, refs='https://bysekoze.com/e/' + VID, origin='https://bysekoze.com')
    print(f'{path} -> HTTP {st} | {b[:150]}')
    time.sleep(1)
