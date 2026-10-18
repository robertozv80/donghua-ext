# -*- coding: utf-8 -*-
"""Sonda v24.14c - status reales?, generos donghua paginados, /directorio."""
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

def slugs(html, limit=6):
    out = []
    for m in re.finditer(r'<a[^>]*href="(/donghua/[a-z0-9-]+)"[^>]*>\s*<img[^>]*alt="([^"]*)"', html):
        if 'episodio' in m.group(1): continue
        out.append((m.group(1).split('/')[-1], m.group(2)[:30]))
        if len(out) >= limit: break
    return out

for name, url in [
    ('donghua p1', 'https://animegratis.net/donghua'),
    ('emision p1', 'https://animegratis.net/donghua?status=En+emisi%C3%B3n'),
    ('finalizado p1', 'https://animegratis.net/donghua?status=Finalizado'),
    ('emision p2', 'https://animegratis.net/donghua?status=En+emisi%C3%B3n&page=2'),
]:
    st, html = get(url)
    print(f'[{name}] HTTP {st} | primeras cards: {slugs(html)}')
    time.sleep(1)

print()
for name, url in [
    ('gen accion', 'https://animegratis.net/donghua/genero/accion'),
    ('gen accion p2', 'https://animegratis.net/donghua/genero/accion?page=2'),
    ('gen artes', 'https://animegratis.net/donghua/genero/artes-marciales'),
    ('gen artes p2', 'https://animegratis.net/donghua/genero/artes-marciales?page=2'),
]:
    st, html = get(url)
    n = len(re.findall(r'href="/donghua/[a-z0-9-]+"', html))
    nx = 'rel="next"' in html
    print(f'[{name}] HTTP {st} len {len(html)} | links {n} | rel=next {nx} | cards: {slugs(html, 3)}')
    time.sleep(1)

print()
st, d = get('https://animegratis.net/directorio')
print('[directorio] HTTP', st, len(d))
print('  ssr-init:', 'ssr-init' in d)
print('  links /anime/:', len(re.findall(r'href="/anime/[a-z0-9-]+"', d)))
m = re.search(r'<a[^>]*href="/anime/[a-z0-9-]+"[^>]*>.{0,800}?</a>', d, re.S)
if m: print('  primera card anime:', m.group(0)[:600])

# home: primera card de episodio
st2, home = get('https://animegratis.net/')
m2 = re.search(r'<a[^>]*data-home-episode-card[^>]*>.{0,900}?</a>', home, re.S)
print()
print('[home card ep]:')
if m2: print(m2.group(0)[:800])
