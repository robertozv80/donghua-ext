# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 7: contexto amplio de los chunks clave del embed."""
import re, sys, gzip, io, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

def fetch(u):
    req = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': 'https://www.terabox.com/'})
    try:
        r = urllib.request.urlopen(req, timeout=40)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return raw.decode('utf-8', 'replace')
    except Exception as e:
        return f'ERR {type(e).__name__}: {e}'

chunks = {
    'DoodStreamVideo': 'https://s5.teraboxcdn.com/fe-opera-static/node-static-v4/fe-webv4-main/js/DoodStreamVideo.c8a96def.js',
    'DoodStreamVideo~share': 'https://s5.teraboxcdn.com/fe-opera-static/node-static-v4/fe-webv4-main/js/DoodStreamVideo~share.f1712bd3.js',
}
KEYS = ['/share/streaming', 'shareInfo', 'sekey', 'getShareInfo', 'shareid', 'M3U8', 'browserid']
for tag, url in chunks.items():
    body = fetch(url)
    if body.startswith('ERR'):
        print(tag, '->', body[:100]); continue
    print('=' * 78)
    print('CHUNK', tag, f'({len(body)//1024}KB)')
    n = 0
    for k in KEYS:
        for m in re.finditer(re.escape(k), body):
            a = max(0, m.start() - 250)
            b = min(len(body), m.end() + 350)
            win = body[a:b].replace('\n', ' ')
            n += 1
            print('-' * 78)
            print(f'[{k}] @{m.start()}')
            print(win)
            if n > 14:
                break
        if n > 14:
            break
