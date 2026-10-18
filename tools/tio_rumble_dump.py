# -*- coding: utf-8 -*-
"""Dump de la respuesta embedJS de rumble y variantes de endpoint."""
import http.cookiejar, urllib.request, urllib.parse, re, sys, gzip, io
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
opener.addheaders = [('User-Agent', UA), ('Accept-Language', 'es-ES,es;q=0.8')]

def get(url, refs=None, to=25):
    h = {'Referer': refs} if refs else {}
    req = urllib.request.Request(url, headers=h)
    try:
        r = opener.open(req, timeout=to)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return r.status, raw.decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

emb = 'https://rumble.com/embed/v4g6xit/?pub=3am9tf'
vid = 'v4g6xit'
variants = [
    f'https://rumble.com/embedJS/u3/?request=video&v={vid}',
    f'https://rumble.com/embedJS.u3/?request=video&v={vid}',
    f'https://rumble.com/embedJS/u3/?request=video&v={vid}&embed={urllib.parse.quote(emb, safe="")}',
    f'https://rumble.com/embedJS/u3/?request=video&v={vid}&referrer=https%3A%2F%2Ftiodonghua.lat',
    f'https://rumble.com/embedJS/u3/?request=video&v={vid}&embed={urllib.parse.quote(emb, safe="")}&referrer=https%3A%2F%2Ftiodonghua.lat',
]
for u in variants:
    st, body = get(u, refs='https://rumble.com/')
    print('=' * 70)
    print(st, len(body), u[:110])
    print(body[:600])
    fixed = body.replace('\\/', '/')
    hls = re.findall(r'"hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"', fixed)
    if hls:
        print('  HLS FOUND:', hls[0][:120])
