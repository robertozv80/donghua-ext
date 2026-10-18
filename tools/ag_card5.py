# -*- coding: utf-8 -*-
"""Sonda v24.15b - AnimeGratis: HTML crudo de card, homepage y ficha de detalle (poster bug)."""
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

print("=== A) HTML crudo de la 1era card /donghua ===")
st, html = fetch('https://animegratis.net/donghua')
i = html.find('href="/donghua/qu-ni-de-dao"')
print(html[i-200:i+1400])

print("\n\n=== B) Homepage (/) - cards y srcset ===")
st, home = fetch('https://animegratis.net/')
print(f'HTTP {st}, {len(home)} bytes, srcset count: {len(re.findall(chr(34)+"srcset"+chr(34), home))}')
for m in re.finditer(r'srcset="([^"]+)"', home):
    print(' srcset:', m.group(1)[:250])
# primera card de episodio
j = home.find('data-home-episode-card')
if j > 0:
    print(home[j-300:j+900])

print("\n\n=== C) Ficha de detalle /donghua/qu-ni-de-dao (poster) ===")
st, det = fetch('https://animegratis.net/donghua/qu-ni-de-dao')
print(f'HTTP {st}, {len(det)} bytes')
print('-- og:image:', re.findall(r'<meta property="og:image"[^>]*content="([^"]*)"', det))
print('-- imgs con cdn:')
for m in re.finditer(r'<img[^>]*cdn\.animegratis[^>]*>', det):
    print('  ', m.group(0)[:260])
print('-- img principal (primera 8):')
for m in list(re.finditer(r'<img[^>]+>', det))[:8]:
    print('  ', m.group(0)[:260])
print('-- ld+json image:', re.findall(r'"image"\s*:\s*("[^"]+")', det))
