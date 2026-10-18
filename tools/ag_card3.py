# -*- coding: utf-8 -*-
"""Sonda v24.14d - cards en genero, ssr-init, home card."""
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
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

st, gen = get('https://animegratis.net/donghua/genero/accion')
print('[genero accion] HTTP', st)
# contexto de un link /donghua/
for m in list(re.finditer(r'<a[^>]*href="/donghua/[a-z0-9-]+"[^>]*>', gen))[:3]:
    seg = gen[m.start():m.start()+1500]
    end = seg.find('</a>')
    print('--- CARD GENERO:')
    print(seg[:end+4].replace('\t', ' ')[:900])
    print()
    break

# ssr-init
st, d = get('https://animegratis.net/directorio')
m = re.search(r'<script[^>]*id="ssr-init"[^>]*>(.*?)</script>', d, re.S)
if m:
    raw = m.group(1).strip()
    print('[ssr-init] len', len(raw), '| head:', raw[:200])
    try:
        j = json.loads(raw)
        print('keys:', list(j.keys()))
        if 'animes' in j and j['animes']:
            print('animes[0]:', json.dumps(j['animes'][0], ensure_ascii=False)[:400])
            print('total animes:', len(j['animes']), '| total:', j.get('total'), '| totalPages:', j.get('totalPages'))
    except Exception as e:
        print('json err:', e, raw[:150])

# home card
st, home = get('https://animegratis.net/')
ix = home.find('data-home-episode-card')
print()
print('[home] idx', ix)
print(home[max(0,ix-300):ix+1000].replace('\t', ' ')[:1300])
