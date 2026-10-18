# -*- coding: utf-8 -*-
"""Sonda v24.14f - JSON completo de embed/details + validacion de streams."""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, sys, time, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, extra=None, to=40):
    h = {'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'same-origin'}
    if refs: h['Referer'] = refs
    if extra: h.update(extra)
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

MD = 'https://www.mundodonghua.com/'

def details(code):
    st, b = get(f'https://bysekoze.com/api/videos/{code}/embed/details', refs=MD)
    return st, b

for vid, code in [('sword-art-2 ep1', 'rgcgmsu5j63y'), ('sword-art ep1', '0nn2ohmw6jyv')]:
    st, b = details(code)
    print('=' * 70)
    print(vid, '| HTTP', st)
    try:
        j = json.loads(b)
        print(json.dumps(j, indent=1, ensure_ascii=False)[:2500])
        # validar streams
        def walk(o, path=''):
            if isinstance(o, dict):
                for k, v in o.items(): walk(v, path + '/' + k)
            elif isinstance(o, list):
                for i, v in enumerate(o): walk(v, f'{path}[{i}]')
            elif isinstance(o, str) and (o.startswith('http') or o.startswith('//')):
                if '.m3u8' in o or '.mp4' in o or 'stream' in path.lower() or 'url' in path.lower():
                    print(f'  STREAM {path}: {o[:130]}')
        walk(j)
    except Exception as e:
        print('no json:', b[:300])
    time.sleep(2)

# validar un stream con Range si aparece
st, b = details('rgcgmsu5j63y')
try:
    j = json.loads(b)
    urls = []
    def walk2(o):
        if isinstance(o, dict):
            for v in o.values(): walk2(v)
        elif isinstance(o, list):
            for v in o: walk2(v)
        elif isinstance(o, str) and (o.startswith('http') or o.startswith('//')):
            urls.append(o)
    walk2(j)
    for u in urls:
        u = u if u.startswith('http') else 'https:' + u
        if '.m3u8' not in u and '.mp4' not in u: continue
        try:
            req = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': 'https://bysekoze.com/',
                                                     'Range': 'bytes=0-63'})
            r = opener.open(req, timeout=30)
            print('VALIDATE', r.status, r.headers.get('Content-Type'), u[:100])
        except Exception as e:
            print('VALIDATE ERR', type(e).__name__, str(e)[:80], u[:100])
except Exception:
    pass
