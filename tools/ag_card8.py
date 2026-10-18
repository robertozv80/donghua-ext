# -*- coding: utf-8 -*-
"""Sonda v24.15e - AnimeGratis: simular parseHomePageCards + paginas con filtros (?status, genero, q)."""
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

def best_image_url(tag):
    for attr in ['src', 'data-src', 'data-fallback', 'data-fb2']:
        am = re.search(r'\s' + attr + r'="([^"]*)"', tag)
        if am and am.group(1).strip() and am.group(1).strip() != 'data:,':
            v = am.group(1).strip()
            if v.startswith('http'):
                return v
    return ''

print("=== A) Simulacion parseHomePageCards sobre / ===")
st, home = fetch('https://animegratis.net/')
cards = re.finditer(r'<a\s+[^>]*data-home-episode-card[^>]*>', home)
count = 0
for m in cards:
    start = m.start()
    end = home.find('</a>', start)
    inner = home[start:end]
    href_m = re.search(r'href="([^"]*)"', m.group(0))
    href = href_m.group(1) if href_m else ''
    if 'episodio' not in href: continue
    img_m = re.search(r'<img[^>]+>', inner)
    if not img_m: continue
    alt_m = re.search(r'alt="([^"]*)"', img_m.group(0))
    title = alt_m.group(1).strip() if alt_m else None
    poster = best_image_url(img_m.group(0))
    clean = re.sub(r'\s*Episodio\s*\d+', '', title or '').strip()
    count += 1
    print(f'{count:2d}. {clean[:45]:45s} | poster={poster[:80]}')
    if count >= 12: break

print("\n\n=== B) /donghua?status=En+emision ===")
st, p = fetch('https://animegratis.net/donghua?status=En+emisi%C3%B3n')
print(f'HTTP {st}, {len(p)}B')
anchors = [(m.start(), m.group(1)) for m in re.finditer(r'<a\s+[^>]*href="(/donghua/[^"]*)"', p)]
print(f'anchors /donghua/*: {len(anchors)}')
shown = 0
for pos, href in anchors:
    if 'episodio' in href or '/genero/' in href: continue
    end = p.find('</a>', pos)
    inner = p[pos:end]
    alt_m = re.search(r'<img[^>]*alt="([^"]*)"', inner)
    title = None
    if alt_m:
        t = re.sub(r'(?i)^Portada de\s*', '', alt_m.group(1)).strip()
        if t: title = t
    if not title: continue
    img_m = re.search(r'<img[^>]+>', inner)
    poster = best_image_url(img_m.group(0)) if img_m else ''
    shown += 1
    print(f'  {title[:40]:40s} | {poster[:85]}')
    if shown >= 8: break

print("\n\n=== C) /donghua/genero/accion ===")
st, p = fetch('https://animegratis.net/donghua/genero/accion')
print(f'HTTP {st}, {len(p)}B')
anchors = [(m.start(), m.group(1)) for m in re.finditer(r'<a\s+[^>]*href="(/donghua/[^"]*)"', p)]
print(f'anchors /donghua/*: {len(anchors)}')
shown = 0
for pos, href in anchors:
    if 'episodio' in href or '/genero/' in href: continue
    end = p.find('</a>', pos)
    inner = p[pos:end]
    alt_m = re.search(r'<img[^>]*alt="([^"]*)"', inner)
    title = None
    if alt_m:
        t = re.sub(r'(?i)^Portada de\s*', '', alt_m.group(1)).strip()
        if t: title = t
    if not title: continue
    img_m = re.search(r'<img[^>]+>', inner)
    poster = best_image_url(img_m.group(0)) if img_m else ''
    shown += 1
    print(f'  {title[:40]:40s} | {poster[:85]}')
    if shown >= 8: break

print("\n\n=== D) /donghua?q= (busqueda) ===")
st, p = fetch('https://animegratis.net/donghua?q=' + 'inmortal')
print(f'HTTP {st}, {len(p)}B')
anchors = [(m.start(), m.group(1)) for m in re.finditer(r'<a\s+[^>]*href="(/donghua/[^"]*)"', p)]
print(f'anchors /donghua/*: {len(anchors)}')
shown = 0
for pos, href in anchors:
    if 'episodio' in href or '/genero/' in href: continue
    end = p.find('</a>', pos)
    inner = p[pos:end]
    alt_m = re.search(r'<img[^>]*alt="([^"]*)"', inner)
    title = None
    if alt_m:
        t = re.sub(r'(?i)^Portada de\s*', '', alt_m.group(1)).strip()
        if t: title = t
    if not title: continue
    img_m = re.search(r'<img[^>]+>', inner)
    poster = best_image_url(img_m.group(0)) if img_m else ''
    shown += 1
    print(f'  {href} | {title[:40]:40s} | {poster[:80]}')
    if shown >= 8: break
