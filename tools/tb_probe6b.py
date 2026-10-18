# -*- coding: utf-8 -*-
"""v24.13 - Terabox parte 6b: keywords literales en los chunks JS del embed."""
import re, sys, gzip, io, urllib.request, time
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'

html = open('tools/tb_embed.html', encoding='utf-8', errors='replace').read()
js = sorted(set(re.findall(r'(https://s5\.teraboxcdn\.com/[^"\'\s()<>]+\.js)', html)))
prio = [u for u in js if re.search(r'(share|embed|player|video|dood|file)', u, re.I) and 'i18n' not in u]
others = [u for u in js if u not in prio]
print('chunks js:', len(js), '| prioritarios:', len(prio))
for u in prio[:12]: print('  ', u.split('/')[-1])

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

KEYS = ['dlink', 'playurl', 'shorturlinfo', '/api/', '/share/', 'm3u8', 'streaming', 'downloadurl', 'filemetas']
seen = set()
targets = prio[:10] + others[:10]
for u in targets:
    body = fetch(u)
    if body.startswith('ERR'):
        print('---', u.split('/')[-1], '->', body[:80]); continue
    hits = []
    for k in KEYS:
        for m in re.finditer(re.escape(k), body):
            a = max(0, m.start() - 90)
            win = body[a:m.start() + 110].replace('\n', ' ')
            # recortar a algo legible
            if win in seen: continue
            seen.add(win)
            hits.append(f'[{k}] ...{win}...')
    if hits:
        print('=' * 70)
        print('CHUNK', u.split('/')[-1], f'({len(body)//1024}KB)')
        for h in hits[:20]: print('   ', h)
    time.sleep(0.4)
