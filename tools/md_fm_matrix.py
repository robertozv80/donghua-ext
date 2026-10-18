# -*- coding: utf-8 -*-
"""Sonda v24.14g - matriz Referer/Origin API bysekoze."""
import http.cookiejar, urllib.request, gzip, io, sys, time, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def api(url, refs=None, origin=None, to=40):
    h = {'User-Agent': UA, 'Accept': '*/*',
         'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'same-origin'}
    if refs: h['Referer'] = refs
    if origin: h['Origin'] = origin
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

MD = 'https://www.mundodonghua.com/'
VID = 'rgcgmsu5j63y'
URL = f'https://bysekoze.com/api/videos/{VID}/embed/details'

cases = [
    ('A ref=MD orig=MD', MD, 'https://www.mundodonghua.com'),
    ('B ref=MD orig=None', MD, None),
    ('C ref=None orig=MD', None, 'https://www.mundodonghua.com'),
    ('D ref=MD orig=byse', MD, 'https://bysekoze.com'),
    ('E ref=ver/1 orig=MD', 'https://www.mundodonghua.com/ver/the-first-immortal-of-sword-art-2/1', 'https://www.mundodonghua.com'),
]
for name, r, o in cases:
    st, b = api(URL, refs=r, origin=o)
    ok = st == 200
    print(f'[{name}] HTTP {st} | {"OK len=%d" % len(b) if ok else b[:100]}')
    time.sleep(3)
