# -*- coding: utf-8 -*-
"""v24.13 - Recheck del embed Rumble de TioDonghua ep15 (v4g6xit):
el HTML vuelve a traer hls-vod? que responde embedJS hoy?
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, ssl, sys, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=30):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    if refs: h['Referer'] = refs
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

embeds = [
    'https://rumble.com/embed/v4g6xit/',       # ep15 TD
    'https://rumble.com/embed/v4g6xit/?pub=3am9tf',
    'https://rumble.com/embed/v4gxp86/',       # ep16 TD
]
UA_MAP = r'"(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"'
for emb in embeds:
    print('=' * 70)
    print('embed:', emb)
    st, html = get(emb, refs='https://tiodonghua.lat/')
    fixed = html.replace('\\/', '/')
    hls = re.findall(r'"hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"', fixed)
    qmap = re.findall(r'"(\d{3,4})":\{"url":"(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"', fixed)
    print(f'  HTML HTTP {st} len {len(html)} | hls-vod {len(hls)} | mapa {len(qmap)}')
    for h in hls[:2]: print('   hls:', h[:130])
    for q, u in qmap[:4]: print(f'   mapa {q}p: {u[:100]}')
    vid = re.search(r'rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)', emb).group(1)
    api = ('https://rumble.com/embedJS/u3/?request=video&v=' + vid +
           '&embed=' + urllib.parse.quote(emb, safe=''))
    st2, j = get(api, refs='https://rumble.com/')
    fixedj = j.replace('\\/', '/')
    ua = re.findall(UA_MAP, fixedj)
    print(f'  API HTTP {st2} len {len(j)} | ua calidades {len(ua)}')
    if st2 == 200 and len(j) < 4000:
        print('  API cuerpo:', j[:1200])
    time.sleep(2)
