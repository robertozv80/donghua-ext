# -*- coding: utf-8 -*-
"""Sonda v24.14b - estructura de cards en animegratis /donghua."""
import http.cookiejar, urllib.request, gzip, io, sys, time, re
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')
def get(url, to=40):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

st, html = get('https://animegratis.net/donghua')
print('HTTP', st, len(html))

# el primer link /donghua/{slug} con su contexto completo
m = re.search(r'<a[^>]*href="/donghua/[a-z0-9-]+"[^>]*>.{0,1800}?</a>', html, re.S)
if m:
    print('===== PRIMERA CARD (contexto <a> completo):')
    print(m.group(0)[:1800])

# links de genero dentro de /donghua
print()
print('===== LINKS DE GENERO EN /donghua:')
genlinks = sorted(set(re.findall(r'href="([^"]*(?:genero|genre)[^"]*)"', html)))
for g in genlinks[:25]: print('  ', g)

# estructura de grid/contenedor
print()
grids = re.findall(r'<(div|section|ul)[^>]*(?:grid|catalog|card)[^>]*>', html)[:8]
print('contenedores:', grids)

# pagination
print()
pag = re.findall(r'href="([^"]*page[^"]*)"', html)[:10]
print('paginacion:', sorted(set(pag))[:8])
next_rel = 'rel="next"' in html
print('rel=next:', next_rel)

# home: cards de episodios
st2, home = get('https://animegratis.net/')
print()
print('===== HOME: data-home-episode-card:', len(re.findall(r'data-home-episode-card', home)))
m2 = re.search(r'<a[^>]*data-home-episode-card[^>]*>.{0,1200}?</a>', home, re.S)
if m2: print(m2.group(0)[:1200])
