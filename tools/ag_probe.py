# -*- coding: utf-8 -*-
"""Sonda v24.14 - AnimeGratis: /donghua, status, busqueda, generos."""
import http.cookiejar, urllib.request, gzip, io, sys, time, re, json
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
        return r.status, _read(r), r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace'), url
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}', url

def retry(fn, *a, **k):
    st, b, u = -1, '', ''
    for i in range(3):
        st, b, u = fn(*a, **k)
        if st == 200: return st, b, u
        time.sleep(2.5 * (i + 1))
    return st, b, u

def summary(url, html):
    # contar links a /donghua/ y /anime/
    d = re.findall(r'href="(/donghua/[^"]+)"', html)
    a = re.findall(r'href="(/anime/[^"]+)"', html)
    dEp = [x for x in d if 'episodio' in x]
    dSer = [x for x in d if 'episodio' not in x]
    ssr = 'script#ssr-init' in html or 'ssr-init' in html
    titles = re.findall(r'<h[23][^>]*>([^<]{4,60})</h[23]>', html)[:6]
    print(f'  links /donghua/ serie={len(dSer)} ep={len(dEp)} | /anime/={len(a)} | ssr-init={ssr}')
    print('  titles:', [t.strip()[:40] for t in titles])
    # generos visibles
    gens = re.findall(r'href="(/directorio/genero/[^"]+|/donghua\?[^"]*genre[^"]*)"[^>]*>([^<]{2,30})<', html)
    if gens: print('  generos:', gens[:8])
    return dSer, a

for name, url in [
    ('home', 'https://animegratis.net/'),
    ('donghua', 'https://animegratis.net/donghua'),
    ('emision', 'https://animegratis.net/donghua?status=En+emisi%C3%B3n'),
    ('finalizado', 'https://animegratis.net/donghua?status=Finalizado'),
    ('emision-v22', 'https://animegratis.net/donghua?q=&status=En+emisi%C3%B3n&genre=&year=&sort=year'),
    ('genero-accion?', 'https://animegratis.net/donghua?genre=accion'),
    ('genero-artes?', 'https://animegratis.net/donghua?genre=artes-marciales'),
    ('busqueda-q', 'https://animegratis.net/donghua?q=jade'),
]:
    st, html, final = retry(get, url)
    print('=' * 66)
    print(f'[{name}] {url}')
    print(f'  HTTP {st} | final {final} | len {len(html)}')
    if st == 200:
        summary(url, html)
        # si es redirect o pagina rara, dump head
        if len(html) < 3000:
            print('  BODY:', html[:500].replace(chr(10), ' '))
    else:
        print('  body:', html[:200].replace(chr(10), ' '))
    time.sleep(1.5)
