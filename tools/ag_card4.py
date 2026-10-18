# -*- coding: utf-8 -*-
"""Sonda v24.15 - AnimeGratis: estructura de cards (srcset) y bug del poster repetido."""
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

print("=== 1) Catalogo /donghua (pagina 1) ===")
st, html = fetch('https://animegratis.net/donghua')
print(f'HTTP {st}, {len(html)} bytes')
# Extraer las primeras cards: buscar <a href="/donghua/..."> con <img>
cards = re.findall(r'<a[^>]*href="(/donghua/[^"]+)"[^>]*>(.*?)</a>', html, re.S)
print(f'cards /donghua encontradas: {len(cards)}')
for href, inner in cards[:6]:
    imgs = re.findall(r'<img[^>]+>', inner)
    title_m = re.search(r'<h[23][^>]*>([^<]+)</h[23]>', inner)
    alt_m = re.search(r'alt="([^"]*)"', inner)
    srcset_m = re.search(r'srcset="([^"]*)"', inner)
    src_m = re.search(r'\ssrc="([^"]*)"', inner)
    print(f'\n--- {href}')
    if title_m: print(f'  h-tag title: {title_m.group(1)[:80]}')
    if alt_m: print(f'  alt: {alt_m.group(1)[:80]}')
    if src_m: print(f'  src: {src_m.group(1)[:120]}')
    if srcset_m: print(f'  srcset: {srcset_m.group(1)[:300]}')

print("\n=== 2) Buscar todos los srcset del documento ===")
all_srcset = re.findall(r'srcset="([^"]+)"', html)
print(f'srcset total en documento: {len(all_srcset)}')
for s in all_srcset[:5]:
    print(' ', s[:200])

print("\n=== 3) ssr-init del directorio (muestra de posters) ===")
st2, dirhtml = fetch('https://animegratis.net/directorio')
m = re.search(r'<script id="ssr-init"[^>]*>(.*?)</script>', dirhtml, re.S)
if m:
    data = m.group(1)
    print(f'ssr-init: {len(data)} chars')
    try:
        obj = json.loads(data)
        animes = obj.get('animes') or []
        print(f'animes: {len(animes)}')
        for a in animes[:4]:
            print(' ', json.dumps({k: a.get(k) for k in ('t','sl','im','fb','fb2','yr')}, ensure_ascii=False)[:300])
    except Exception as e:
        print('JSON parse error:', e)
        print(data[:300])
