# -*- coding: utf-8 -*-
"""Sonda v24.14f - repr de la card del home."""
import http.cookiejar, urllib.request, gzip, io, sys, re
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')
h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5'}
r = opener.open(urllib.request.Request('https://animegratis.net/', headers=h), timeout=40)
home = _read(r)

ms = list(re.finditer(r'data-home-episode-card', home))
print('hits:', len(ms))
# tomar el hit que este dentro de una <a (el primero tras el CSS)
for m in ms:
    ctx = home[m.start()-120:m.start()+1500]
    if '<a' in home[m.start()-400:m.start()]:
        ix = home.rfind('<a', m.start()-400, m.start())
        seg = home[ix:ix+2200]
        end = seg.find('</a>')
        if end > 0:
            print('---- CARD REAL ----')
            print(seg[:end+4].replace('\t', ' ')[:2000])
            break
