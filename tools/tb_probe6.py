# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 6: averiguar que API llama el player del embed.
Descarga los chunks JS del HTML del embed (tools/tb_embed.html) y busca endpoints.
"""
import re, sys, gzip, io, http.cookiejar, urllib.request, urllib.parse, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

html = open('tools/tb_embed.html', encoding='utf-8', errors='replace').read()

# todos los js del CDN fe-webv4
js = sorted(set(re.findall(r'(https://s5\.teraboxcdn\.com/[^"\'\s()<>]+\.js)', html)))
print('chunks js:', len(js))
# priorizar los que suenan a share/embed/player
prio = [u for u in js if re.search(r'(share|embed|player|video|dood)', u, re.I)]
others = [u for u in js if u not in prio]
print('prioritarios:')
for u in prio[:15]: print('  ', u.split("/")[-1])

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

pats = [
    (r'"(/[a-z]+/api/[a-zA-Z_/]+)"', 'path /x/api/...'),
    (r'"(/api/[a-zA-Z_/]+)"', 'path /api/...'),
    (r'"(/share/[a-zA-Z_/]+)"', 'path /share/...'),
    (r'(["\'])((?:(?!\1)[a-zA-Z0-9_./-]*dlink(?:(?!\1)[a-zA-Z0-9_./-]*))\1', 'dlink refs'),
    (r'(["\'])([a-zA-Z0-9_./-]*m3u8[a-zA-Z0-9_./-]*)\1', 'm3u8 refs'),
    (r'(["\'])([a-zA-Z0-9_./-]*(?:playurl|streaming|downloadurl|filemetas|shorturlinfo)[a-zA-Z0-9_./-]*)\1', 'otras APIs'),
]
seen = set()
targets = prio[:8] + others[:8]
for u in targets:
    body = fetch(u)
    if body.startswith('ERR'):
        print('---', u.split('/')[-1], '->', body[:80]); continue
    hits = []
    for pat, tag in pats:
        for m in re.findall(pat, body):
            s = m[1] if isinstance(m, tuple) else m
            s = s.strip('"\'')
            if len(s) > 4 and s not in seen:
                seen.add(s)
                hits.append(f'[{tag}] {s}')
    if hits:
        print('=' * 70)
        print('CHUNK', u.split('/')[-1], f'({len(body)//1024}KB)')
        for h in hits[:25]: print('   ', h)
    time.sleep(0.5)
