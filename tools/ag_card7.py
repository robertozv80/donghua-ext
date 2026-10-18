# -*- coding: utf-8 -*-
"""Sonda v24.15d - AnimeGratis: card homepage cruda + simulacion del parseo del provider + ssr-init."""
import http.cookiejar, urllib.request, gzip, io, re, sys, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def fetch(url, referer=None, to=40):
    h = {'User-Agent': UA, 'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
         'Accept-Language': 'es-ES,es;q=0.8,en-US;q=0.5'}
    if referer: h['Referer'] = referer
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return r.status, raw.decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

print("=== A) Card homepage cruda (a data-home-episode-card) ===")
st, home = fetch('https://animegratis.net/')
# buscar el tag <a ... data-home-episode-card ...>
i = home.find('<a ')
while i >= 0:
    j = home.find('data-home-episode-card', i)
    if j == -1: break
    # retroceder al inicio del <a
    start = home.rfind('<a ', 0, j)
    end = home.find('</a>', j)
    print(home[start:end+4][:2500])
    print('...')
    break

print("\n\n=== B) Simulacion parseDonghuaCards sobre /donghua ===")
st, cat = fetch('https://animegratis.net/donghua')
# jsoup: a[href^="/donghua/"] en orden de documento
anchors = [(m.start(), m.group(1)) for m in re.finditer(r'<a\s+[^>]*href="(/donghua/[^"]*)"', cat)]
print(f'anchors /donghua/*: {len(anchors)}')
seen = set()
for pos, href in anchors[:40]:
    if 'episodio' in href or '/genero/' in href: continue
    # extraer el bloque del anchor
    end = cat.find('</a>', pos)
    inner = cat[pos:end]
    # extractCardTitle: alt del img
    alt_m = re.search(r'<img[^>]*alt="([^"]*)"', inner)
    title = None
    if alt_m:
        clean = re.sub(r'(?i)^Portada de\s*', '', alt_m.group(1)).strip()
        if clean: title = clean
    if not title:
        h3 = re.search(r'<h3[^>]*>([^<]+)</h3>', inner)
        if h3: title = h3.group(1).strip()
    # poster: primer img del anchor, bestImageUrl (src, data-src, data-fallback, data-fb2)
    poster = ''
    img_m = re.search(r'<img[^>]+>', inner)
    if img_m:
        tag = img_m.group(0)
        for attr in ['src', 'data-src', 'data-fallback', 'data-fb2']:
            am = re.search(r'\s' + attr + r'="([^"]*)"', tag)
            if am and am.group(1).strip() and am.group(1).strip() != 'data:,':
                poster = am.group(1).strip()
                if poster.startswith('http'): break
    key = href
    dup = ' [DUP-URL]' if key in seen else ''
    seen.add(key)
    print(f'{href} | title={title!r} | poster={poster[:90]}{dup}')

print("\n\n=== C) ssr-init de /directorio ===")
st, dirhtml = fetch('https://animegratis.net/directorio')
m = re.search(r'<script[^>]*id="ssr-init"[^>]*>(.*?)</script>', dirhtml, re.S)
if m:
    try:
        obj = json.loads(m.group(1))
        animes = obj.get('animes') or []
        print(f'animes: {len(animes)}')
        for a in animes[:5]:
            print(' ', json.dumps({k: a.get(k) for k in ('t','sl','im','fb','fb2','yr','ty')}, ensure_ascii=False)[:320])
    except Exception as e:
        print('parse err:', e, m.group(1)[:200])
