# -*- coding: utf-8 -*-
"""Sonda v24.14i - simula parseDonghuaCards/getMainPage/search nuevos contra el sitio."""
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

def parse_donghua_cards(html):
    """Equivalente a parseDonghuaCards v24.14: a[href^=/donghua/], saltar
    episodio y /genero/, titulo del alt sin 'Portada de '."""
    out = []
    seen = set()
    for m in re.finditer(r'<a[^>]*href="(/donghua/[^"]*)"[^>]*>(.*?)</a>', html, re.S):
        href, inner = m.group(1), m.group(2)
        if 'episodio' in href or '/genero/' in href: continue
        am = re.search(r'<img[^>]*alt="([^"]*)"', inner)
        title = None
        if am:
            t = am.group(1).strip()
            t = re.sub(r'^Portada de\s*', '', t, flags=re.I).strip()
            if t: title = t
        if title is None:
            hm = re.search(r'<h[23][^>]*>([^<]+)</h[23]>', inner)
            if hm: title = hm.group(1).strip()
        if not title: continue
        im = re.search(r'<img[^>]*src="([^"]+)"', inner)
        poster = im.group(1) if im else ''
        key = 'https://animegratis.net' + href
        if key in seen: continue
        seen.add(key)
        out.append((title, key, poster))
    return out

cases = [
    ('Donghuas', 'https://animegratis.net/donghua', 1),
    ('En Emision', 'https://animegratis.net/donghua?status=En+emisi%C3%B3n', 1),
    ('Finalizados', 'https://animegratis.net/donghua?status=Finalizado', 1),
    ('Accion', 'https://animegratis.net/donghua/genero/accion', 1),
    ('Artes Marciales', 'https://animegratis.net/donghua/genero/artes-marciales', 1),
    ('Donghuas p2', 'https://animegratis.net/donghua?page=2', 2),
    ('Accion p2', 'https://animegratis.net/donghua/genero/accion?page=2', 2),
]
for name, url, page in cases:
    st, html = get(url)
    cards = parse_donghua_cards(html)
    nxt = 'rel="next"' in html
    print(f'[{name}] HTTP {st} | cards={len(cards)} | hasNext={nxt}')
    for t, u, p in cards[:4]:
        print(f'    - {t[:42]:42s} {u[:60]}')

print()
for q in ['jade', 'battle', 'ren']:
    from urllib.parse import quote
    st, html = get('https://animegratis.net/donghua?q=' + quote(q))
    cards = parse_donghua_cards(html)
    print(f'[search "{q}"] HTTP {st} | resultados={len(cards)}')
    for t, u, p in cards[:3]:
        print(f'    - {t[:45]}')
    time.sleep(1)
