# -*- coding: utf-8 -*-
"""Sonda v24.14h - JSON completo (Origin MD) + validacion de streams."""
import http.cookiejar, urllib.request, gzip, io, sys, time, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, origin=None, to=40):
    h = {'User-Agent': UA, 'Accept': '*/*',
         'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Site': 'same-origin'}
    if refs: h['Referer'] = refs
    if origin: h['Origin'] = origin
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

for vid, code in [('SA2 ep1', 'rgcgmsu5j63y'), ('SA1 ep1', '0nn2ohmw6jyv')]:
    st, b = get(f'https://bysekoze.com/api/videos/{code}/embed/details', origin='https://www.mundodonghua.com')
    print('=' * 70)
    print(vid, '| HTTP', st)
    try:
        print(json.dumps(json.loads(b), indent=1, ensure_ascii=False))
    except Exception:
        print(b[:400])
    time.sleep(3)

# validar un stream del primero
st, b = get('https://bysekoze.com/api/videos/rgcgmsu5j63y/embed/details', origin='https://www.mundodonghua.com')
try:
    j = json.loads(b)
    urls = []
    def walk(o):
        if isinstance(o, dict):
            for v in o.values(): walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
        elif isinstance(o, str) and (o.startswith('http') or o.startswith('//')):
            urls.append(o)
    walk(j)
    for u in urls:
        u = u if u.startswith('http') else 'https:' + u
        if '.m3u8' not in u and '.mp4' not in u: continue
        try:
            req = urllib.request.Request(u, headers={'User-Agent': UA, 'Origin': 'https://bysekoze.com',
                                                     'Referer': 'https://bysekoze.com/', 'Range': 'bytes=0-63'})
            r = opener.open(req, timeout=30)
            data = r.read(24)
            print('VALIDATE', r.status, r.headers.get('Content-Type'), '| magic:', data[:12], '|', u[:110])
        except Exception as e:
            print('VALIDATE ERR', type(e).__name__, str(e)[:70], '|', u[:110])
        time.sleep(1)
except Exception as e:
    print('parse err', e)
