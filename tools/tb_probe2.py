# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 2: share/list sin login funciona (errno 0).
Probar: dlink en la respuesta, descarga con Range, cookies necesarias.
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, ssl, sys, time, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, to=40, extra=None):
    h = {'User-Agent': UA, 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5',
         'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'}
    if refs: h['Referer'] = refs
    if extra: h.update(extra)
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r), r.headers, r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace'), e.headers, e.geturl()
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}', None, url

def retry(fn, *a, **k):
    st, b, hh, fu = -1, '', None, ''
    for i in range(4):
        st, b, hh, fu = fn(*a, **k)
        if st == 200:
            return st, b, hh, fu
        time.sleep(2.5 * (i + 1))
    return st, b, hh, fu

surl = 'NFqQvtSCh-IORMMEksjxHQ'
fid = '848169177574147'

print('=== share/list completo (sin login) ===')
u = f'https://www.terabox.com/share/list?app_id=250528&web=1&channel=dubox&clienttype=0&shorturl={surl}&root=1'
st, body, hh, fu = retry(get, u, refs='https://terabox.com/')
print('HTTP', st, 'len', len(body), '| final url:', fu[:120])
try:
    j = json.loads(body)
    print('errno:', j.get('errno'))
    for it in j.get('list', []):
        print('-' * 60)
        for k in ['fs_id', 'path', 'size', 'md5', 'dlink', 'category', 'server_filename']:
            v = it.get(k)
            if k == 'dlink' and v:
                v = v[:180] + ('...' if len(it[k]) > 180 else '')
            print(f'  {k}: {v}')
    items = j.get('list', [])
    dlink = next((it.get('dlink') for it in items if it.get('fs_id') == fid), None) \
        or (items[0].get('dlink') if items else None)
    if dlink:
        print('=' * 60)
        print('=== probar dlink (Range 0-1023) ===')
        # sin referer primero
        st2, b2, h2, fu2 = get(dlink + '&origin=dlna', refs=None, to=60,
                               extra={'Range': 'bytes=0-1023'})
        ct = h2.get('Content-Type') if h2 else '?'
        clen = h2.get('Content-Range') or h2.get('Content-Length') if h2 else '?'
        print('dlink+origin=dlna sin referer: HTTP', st2, '| CT', ct, '| range/len', clen, '| len', len(b2))
        print('  magic:', b2[:16])
        # con referer terabox
        st3, b3, h3, fu3 = get(dlink, refs='https://www.terabox.com/', to=60,
                               extra={'Range': 'bytes=0-1023'})
        ct3 = h3.get('Content-Type') if h3 else '?'
        print('dlink con referer terabox: HTTP', st3, '| CT', ct3, '| len', len(b3), '| magic:', b3[:16])
        print('  final url:', fu3[:160])
    else:
        print('SIN dlink en share/list -> buscar otra via')
except Exception as e:
    print('parse error:', e)
    print(body[:800])
