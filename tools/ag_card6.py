# -*- coding: utf-8 -*-
"""Sonda v24.15c - AnimeGratis: varias fichas de detalle + card cruda de homepage + directorio."""
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

print("=== A) Varias fichas /donghua: primer img cdn + og:image ===")
for slug in ['qu-ni-de-dao', 'the-first-immortal-of-sword-art', 'demon-slayer-kimetsu-no-yaiba', 'one-piece']:
    st, det = fetch(f'https://animegratis.net/donghua/{slug}')
    if st != 200:
        print(f'{slug}: HTTP {st}')
        continue
    imgs = re.findall(r'<img[^>]+>', det)
    cdn_imgs = [i for i in imgs if 'cdn.animegratis' in i]
    first_cdn = cdn_imgs[0] if cdn_imgs else '(ninguno)'
    og = re.findall(r'<meta property="og:image"[^>]*content="([^"]*)"', det)
    # ¿hay srcset en la ficha?
    ss = re.findall(r'srcset="([^"]+)"', det)
    print(f'\n{slug} (HTTP {st}, {len(det)}B):')
    print(f'  primer cdn img: {first_cdn[:200]}')
    print(f'  og:image: {og[:1]}')
    print(f'  srcset count: {len(ss)}')
    for s in ss[:3]: print(f'    {s[:200]}')

print("\n\n=== B) Ficha /anime/ (tipo anime) ===")
st, det = fetch('https://animegratis.net/anime/meitantei-precure')
imgs = re.findall(r'<img[^>]+>', det)
cdn_imgs = [i for i in imgs if 'cdn.animegratis' in i]
print(f'HTTP {st}: primer cdn img: {(cdn_imgs[0] if cdn_imgs else "(ninguno)")[:220]}')
og = re.findall(r'<meta property="og:image"[^>]*content="([^"]*)"', det)
print(f'og:image: {og[:1]}')

print("\n\n=== C) Card cruda de homepage (data-home-episode-card) ===")
st, home = fetch('https://animegratis.net/')
i = home.find('data-home-episode-card')
print(home[i-100:i+1800])

print("\n\n=== D) /directorio: ssr-init? ===")
st, dirhtml = fetch('https://animegratis.net/directorio')
print(f'HTTP {st}, {len(dirhtml)} bytes')
print('ssr-init present:', 'ssr-init' in dirhtml)
m = re.search(r'<script[^>]*id="ssr-init"[^>]*>(.*?)</script>', dirhtml, re.S)
if m:
    print('ssr-init len:', len(m.group(1)))
else:
    # buscar scripts con JSON
    for m2 in re.finditer(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', dirhtml, re.S):
        print('application/json script:', m2.group(1)[:200])
