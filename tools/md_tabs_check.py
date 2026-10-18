# -*- coding: utf-8 -*-
"""Sonda v24.14y - tipo de pagina de vgembed/vidhidepro + master m3u8 del fm."""
import http.cookiejar, urllib.request, gzip, io, sys, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')
def get(url, refs=None, origin=None, to=40, rng=None):
    h = {'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5'}
    if refs: h['Referer'] = refs
    if origin: h['Origin'] = origin
    if rng: h['Range'] = rng
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

for name, url in [('vgembed', 'https://vgembed.com/e/ao9rxoRVPdM5yGe'),
                  ('vidhidepro', 'https://vidhidepro.com/v/196xwvpiprwq'),
                  ('embedwish', 'https://embedwish.com/e/gsyxume1196m')]:
    st, b = get(url, refs='https://www.mundodonghua.com/')
    isByse = 'Byse Frontend' in b or 'video-embed-mode' in b
    isPacked = 'eval(function(p,a,c,k,e' in b
    print(f'[{name}] HTTP {st} len {len(b)} | ByseSPA={isByse} | packed={isPacked}')
    if not isByse and not isPacked:
        print('   head:', b[:200].replace(chr(10), ' '))
    time.sleep(2)

# master m3u8 del fm (del run anterior, el token expira a las 09:32 -> regenerar no necesario para chequear estructura; usamos el guardado si sigue vivo)
