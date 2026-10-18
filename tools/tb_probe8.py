# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 8: share.c0f20daa.js -> quien llena shareInfo? que hash usa el sign?"""
import re, sys, gzip, io, urllib.request
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

def fetch(u):
    req = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': 'https://www.terabox.com/'})
    try:
        r = urllib.request.urlopen(req, timeout=60)
        raw = r.read()
        if r.headers.get('Content-Encoding') == 'gzip':
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return raw.decode('utf-8', 'replace')
    except Exception as e:
        return f'ERR {type(e).__name__}: {e}'

body = fetch('https://s5.teraboxcdn.com/fe-opera-static/node-static-v4/fe-webv4-main/js/share.c0f20daa.js')
print('chunk len', len(body))
if body.startswith('ERR'):
    print(body); sys.exit(1)
open('tools/tb_share_chunk.js', 'w', encoding='utf-8', errors='replace').write(body)

def dump(key, n=10, w=280):
    ms = list(re.finditer(key, body, re.I))
    print('=' * 78)
    print(f'KEY {key} -> {len(ms)} matches')
    for m in ms[:n]:
        a = max(0, m.start() - w // 2)
        win = body[a:m.end() + w].replace('\n', ' ')
        print('-' * 70)
        print(win)

# donde se llena el store
dump(r'setShareInfo|SET_SHARE|shareInfo\s*[=:]', n=12)
# posibles endpoints
dump(r'shorturlinfo|/share/info|/share/query|embedinfo|/api/share', n=12)
# hash del sign
dump(r'md5|sha1|createHash|Digest', n=12)
