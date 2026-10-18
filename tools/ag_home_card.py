# -*- coding: utf-8 -*-
"""Sonda v24.14e - la <a data-home-episode-card> real del home."""
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

ms = list(re.finditer(r'<a[^>]*data-home-episode-card[^>]*>', home))
print('etiquetas a:', len(ms))
if ms:
    seg = home[ms[0].start():ms[0].start()+1600]
    end = seg.find('</a>')
    print(seg[:end+4][:1500])
