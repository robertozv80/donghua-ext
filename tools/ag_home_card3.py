# -*- coding: utf-8 -*-
"""Sonda v24.14g - links de episodio en el home."""
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

epLinks = re.findall(r'href="(/(?:anime|donghua)/[a-z0-9-]+/episodio[^"]*)"', home)
print('links episodio:', len(epLinks), epLinks[:5])

for pat in ['episodio-', '/ver/', 'episode']:
    ix = home.find(pat)
    print(f'--- primer "{pat}" @ {ix}')
    if ix > 0:
        ix2 = home.rfind('<a', max(0, ix-600), ix)
        seg = home[ix2:ix2+1800]
        end = seg.find('</a>')
        print(seg[:end+4].replace('\t', ' ')[:1400])
        print()
        break
