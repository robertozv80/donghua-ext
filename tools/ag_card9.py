# -*- coding: utf-8 -*-
"""Sonda v24.15f - AnimeGratis: verificar HTTP de las URLs de poster (cdn) con/sin Referer."""
import http.cookiejar, urllib.request, gzip, io, re, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def probe(url, referer=None, to=30):
    h = {'User-Agent': UA, 'Accept': 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
         'Accept-Language': 'es-ES,es;q=0.8'}
    if referer: h['Referer'] = referer
    try:
        req = urllib.request.Request(url, headers=h)
        r = opener.open(req, timeout=to)
        raw = r.read(200)
        ct = r.headers.get('Content-Type', '')
        return r.status, ct, len(raw)
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get('Content-Type', ''), 0
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}', 0

tests = [
    # (url, referer)
    ('https://cdn.animegratis.net/donghua/qu-ni-de-dao/images/cover.jpg', None),
    ('https://cdn.animegratis.net/donghua/qu-ni-de-dao/images/cover.jpg', 'https://animegratis.net/donghua/qu-ni-de-dao'),
    ('https://cdn.animegratis.net/donghua/the-first-immortal-of-sword-art/images/cover.webp', None),
    ('https://cdn.animegratis.net/donghua/the-first-immortal-of-sword-art/images/cover.webp', 'https://animegratis.net/donghua/the-first-immortal-of-sword-art'),
    # srcset variants de la homepage
    ('https://cdn.animegratis.net/anime/meitantei-precure/images/cover-180x270.webp', None),
    ('https://cdn.animegratis.net/anime/meitantei-precure/images/cover-240x360.webp', None),
    ('https://cdn.animegratis.net/anime/meitantei-precure/images/cover.webp', None),
    ('https://cdn.animegratis.net/anime/meitantei-precure/images/cover.webp', 'https://animegratis.net/'),
    # cdn alternativo jkdesa
    ('https://cdn.jkdesa.com/assets/images/animes/image/biao-ren-er.jpg', None),
    ('https://cdn.jkdesa.com/assets/images/animes/image/biao-ren-er.jpg', 'https://animegratis.net/'),
    # og image
    ('https://animegratis.net/api/og/qu-ni-de-dao.png?kind=donghua&v=20260927b', None),
    ('https://animegratis.net/api/og/qu-ni-de-dao.png?kind=donghua&v=20260927b', 'https://animegratis.net/donghua/qu-ni-de-dao'),
]
for url, ref in tests:
    st, ct, n = probe(url, ref)
    print(f'[{st}] {ct:30s} ref={ref is not None!s:5s} {url[:95]}')

# ¿existe cover.jpg para the-first-immortal (solo tiene .webp en el HTML)?
print()
for name in ['cover.jpg', 'cover.webp', 'cover.png', 'cover-240x360.webp', 'cover-180x270.webp']:
    url = f'https://cdn.animegratis.net/donghua/the-first-immortal-of-sword-art/images/{name}'
    st, ct, n = probe(url)
    print(f'the-first-immortal {name}: [{st}] {ct}')
