# -*- coding: utf-8 -*-
"""v24.13 - Sonda Rumble en SeriesDonghua:
1) episodios del home -> csrf + video_id/server_index -> POST /api/player/get-server
2) para embeds rumble: (a) HTML actual que usa extractSdRumble, (b) API embedJS,
   (c) API embedJS con warm-up __cf_bm (visitar embed antes).
"""
import http.cookiejar, urllib.request, urllib.parse, re, gzip, io, ssl, sys, time, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
ctx = ssl.create_default_context()
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj),
                                     urllib.request.HTTPSHandler(context=ctx))

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

def post(url, data, refs=None, to=30, extra=None):
    h = {'User-Agent': UA, 'Content-Type': 'application/x-www-form-urlencoded'}
    if refs: h['Referer'] = refs
    if extra: h.update(extra)
    body = urllib.parse.urlencode(data).encode()
    try:
        r = opener.open(urllib.request.Request(url, data=body, headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

def retry(fn, *a, **k):
    st, b = -1, ''
    for i in range(4):
        st, b = fn(*a, **k)
        if st == 200:
            return st, b
        time.sleep(2.0 * (i + 1))
    return st, b

UA_MAP = r'"(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"'

print('=== 1) HOME ===')
st, home = retry(get, 'https://seriesdonghua.com/')
print('home HTTP', st, 'len', len(home))
raw_eps = list(dict.fromkeys(re.findall(r'href="([^"]*?[a-z0-9-]+-episodio-\d+)/?"', home)))
eps = []
for e in raw_eps:
    if not e.startswith('http'):
        e = 'https://seriesdonghua.com' + ('' if e.startswith('/') else '/') + e
    if 'seriesdonghua.com' in e:
        eps.append(e)
eps = list(dict.fromkeys(eps))
print('episodios en home:', len(eps), '| raw:', len(raw_eps))
for e in eps[:8]: print('  ', e)
if not eps: sys.exit(1)

rumble_embeds = []
for ep in eps[:3]:
    print('=' * 70)
    print('EP', ep)
    st, html = retry(get, ep)
    if st != 200:
        print('  HTTP', st); continue
    csrf = re.search(r'name="csrf-token"\s+content="([^"]+)"', html)
    tags = re.findall(r'<button[^>]*server-tab-btn[^>]*>', html)
    servers = []
    for t in tags:
        vid = re.search(r'data-video-id="([^"]+)"', t)
        sidx = re.search(r'data-server-index="([^"]+)"', t)
        if vid and sidx:
            start = html.find(t)
            window = html[start:start + 500]
            text = re.search(r'>([^<>]{1,40})</button>', window)
            servers.append((vid.group(1), sidx.group(1), text.group(1).strip() if text else '?'))
    print('  csrf:', bool(csrf), '| botones:', [(v, i, l) for v, i, l in servers])
    if not csrf:
        continue
    for vid, sidx, lbl in servers:
        st2, body = post('https://seriesdonghua.com/api/player/get-server',
                         {'video_id': vid, 'server_index': sidx},
                         refs=ep,
                         extra={'X-CSRF-TOKEN': csrf.group(1), 'X-Requested-With': 'XMLHttpRequest',
                                'Accept': 'application/json'})
        emb = ''
        if st2 == 200:
            try:
                j = json.loads(body)
                emb = (j.get('embed_url') or '').replace('\\/', '/')
            except Exception:
                emb = ''
        print(f'  server[{sidx}] "{lbl}" -> HTTP {st2} embed: {emb[:110]}')
        if 'rumble.com' in emb:
            rumble_embeds.append((ep, lbl, emb))
    time.sleep(2)

print('=' * 70)
print('=== 2) RUMBLE: HTML actual (extractSdRumble) vs API embedJS (v24.12) ===')
if not rumble_embeds:
    print('ningun embed rumble en la muestra')
for ep, lbl, emb in rumble_embeds:
    print('-' * 70)
    print('embed:', emb)
    st, html = get(emb, refs='https://seriesdonghua.com/')
    fixed = html.replace('\\/', '/')
    hls = re.findall(r'"hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"', fixed)
    qmap = re.findall(r'"(\d{3,4})":\{"url":"(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"', fixed)
    mp4s = re.findall(r'https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4', fixed)
    print(f'  (a) HTML HTTP {st} len {len(html)} | hls-vod {len(hls)} | mapa calidades {len(qmap)} | mp4 sueltos {len(mp4s)}')
    vid = re.search(r'rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)', emb)
    if not vid:
        print('  sin vid'); continue
    api = ('https://rumble.com/embedJS/u3/?request=video&v=' + vid.group(1) +
           '&embed=' + urllib.parse.quote(emb, safe=''))
    st2, j = get(api, refs='https://rumble.com/')
    fixedj = j.replace('\\/', '/')
    ua = re.findall(UA_MAP, fixedj)
    print(f'  (b) API embedJS HTTP {st2} len {len(j)} | calidades ua {len(ua)} | tiene "ua": {chr(34) + "ua" + chr(34) in j}')
    if st2 == 200 and len(ua):
        for h_, u in ua[:6]: print(f'      {h_}p {u[:100]}')
    if st2 != 200 or not len(ua):
        get(emb, refs='https://seriesdonghua.com/')
        st3, j2 = get(api, refs=emb)
        ua2 = re.findall(UA_MAP, j2.replace('\\/', '/'))
        print(f'  (c) API con warm-up __cf_bm HTTP {st3} len {len(j2)} | calidades ua {len(ua2)}')
        for h_, u in ua2[:6]: print(f'      {h_}p {u[:100]}')
    time.sleep(2)
