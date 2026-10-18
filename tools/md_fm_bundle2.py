# -*- coding: utf-8 -*-
"""Sonda v24.14k - bundles de bysekoze/n1mwq: rutas API y PoW."""
import http.cookiejar, urllib.request, gzip, io, sys, time, re
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=60):
    h = {'User-Agent': UA, 'Accept': '*/*'}
    if refs: h['Referer'] = refs
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

def retry(fn, *a, **k):
    st, b = -1, ''
    for i in range(3):
        st, b = fn(*a, **k)
        if st == 200: return st, b
        time.sleep(2.5 * (i + 1))
    return st, b

for base, bundle in [('https://n1mwq.org', 'videoPagesBundle-Bgi0QmPo.js'),
                     ('https://bysekoze.com', 'videoPagesBundle-Bgi0QmPo.js'),
                     ('https://bysekoze.com', 'pow-DEJGtdh2.js')]:
    st, js = retry(get, f'{base}/assets/{bundle}', refs=base)
    print('=' * 70)
    print(f'{base}/{bundle} HTTP {st} len {len(js)}')
    if st != 200: continue
    fn = 'tools/md_' + bundle.replace('.js', '') + ('_n1' if base.startswith('https://n1') else '') + '.js'
    open(fn, 'w', encoding='utf-8').write(js)
    # rutas api
    apis = sorted(set(re.findall(r'["\'](/api/[a-zA-Z0-9/_${}.-]+)["\']', js)))
    print('API paths:', apis)
    # patrones fetch con template
    tpl = re.findall(r'`[^`]{0,100}api[^`]{0,100}`', js)
    print('API templates:', sorted(set(tpl))[:15])
    # pow
    pows = re.findall(r'.{80}pow.{120}', js, re.I)
    for p in pows[:6]: print('POW ctx:', p.replace('\n', ' ')[:200])
    # headers custom
    hdrs = sorted(set(re.findall(r'["\']([Xx]-[A-Za-z-]+)["\']', js)))
    print('Custom headers:', hdrs)
    # sources / m3u8 / hls
    for kw in ['m3u8', 'mp4', '.ts', 'hls', 'playback', 'stream', 'video-src', 'videoSrc', 'source']:
        hits = [m.start() for m in re.finditer(re.escape(kw), js)][:3]
        for ix in hits:
            print(f'[{kw}] ...{js[max(0,ix-70):ix+110]}...'.replace('\n', ' '))
    time.sleep(1)
