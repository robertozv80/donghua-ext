# -*- coding: utf-8 -*-
"""v24.13 - E2E:
A) TioDonghua: ep15/ep16 -> AJAX 1..8 -> embeds -> tdNormalizeEmbed ->
   prioridad -> orden de emision resultante.
B) SeriesDonghua: 1 episodio real -> rumble embed -> flujo nuevo
   (API -> warm-up -> HTML hls-vod) y validar enlaces resultantes (206).
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
    try:
        r = opener.open(urllib.request.Request(url, data=urllib.parse.urlencode(data).encode(), headers=h), timeout=to)
        return r.status, _read(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}'

def retry(fn, *a, **k):
    st, b = -1, ''
    for i in range(3):
        st, b = fn(*a, **k)
        if st == 200:
            return st, b
        time.sleep(2.5 * (i + 1))
    return st, b

def td_normalize(raw):
    t = raw.replace('\\"', '"').replace('\\/', '/').strip()
    if t.startswith('http'):
        end = next((i for i, ch in enumerate(t) if ch in '"\\'), -1)
        return (t if end == -1 else t[:end]).strip() or None
    if '<iframe' in t.lower() or '<div' in t.lower():
        m = re.search(r'src\s*=\s*["\']([^"\']+)["\']', t, re.I)
        if m: return m.group(1).strip()
    return None

def td_priority(u):
    u = (u or '').lower()
    if 'rumble.com' in u: return 3
    if 'dailymotion.com' in u or 'dai.ly' in u: return 3
    if 'drive.google.com' in u: return 2
    if 'ok.ru' in u or 'odnoklassniki' in u: return 1
    return 0

def host_lbl(u):
    u = u.lower()
    for k, v in [('rumble', 'Rumble'), ('dailymotion', 'Dailymotion'), ('drive.google', 'Drive'),
                 ('ok.ru', 'OK'), ('terabox', 'TB'), ('streamtape', 'ST'), ('voe', 'VOE'),
                 ('embedme', 'Filemon'), ('fmoon', 'Filemon'), ('d000d', 'DS'), ('do0od', 'DS'),
                 ('asnwish', 'SW'), ('obeywish', 'SW'), ('tioplayer', 'MultiPlayer'),
                 ('player.tiodonghua', 'MultiPlayer'), ('playerp2p', 'P2P')]:
        if k in u: return v
    m = re.match(r'https?://([^/]+)', u or '')
    return 'other:' + (m.group(1) if m else '?')

print('================ A) TIODONGHUA: orden de emision ================')
TIOD = 'https://tiodonghua.lat'
for ep_url in [TIOD + '/episodios/the-secrets-of-star-divine-arts-episodio-15-sub-espanol/',
               TIOD + '/episodios/the-secrets-of-star-divine-arts-episodio-27-sub-espanol/']:
    st, eh = retry(get, ep_url)
    mp = re.search(r'postid-(\d+)', eh)
    print('-', ep_url.rsplit('/', 2)[-2], '| post', mp.group(1) if mp else None)
    results = []
    for nume in range(1, 9):
        st2, body = post(TIOD + '/wp-admin/admin-ajax.php',
                         {'action': 'doo_player_ajax', 'post': mp.group(1),
                          'nume': str(nume), 'type': 'tv'}, refs=ep_url)
        emb = ''
        if st2 == 200:
            m = re.search(r'"embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
            if m: emb = m.group(1)
        norm = td_normalize(emb)
        if norm:
            results.append((norm, nume))
        time.sleep(0.6)
    ordered = sorted(results, key=lambda r: -td_priority(td_normalize(r[0]) or ''))
    print('  orden de emision (tras prioridad):')
    for u, nume in ordered:
        print(f'   [{td_priority(u)}] nume {nume} {host_lbl(u):14s} {u[:80]}')
    time.sleep(2)

print('================ B) SERIESDONGHUA: extractSdRumble nuevo ================')
ep = 'https://seriesdonghua.com/combat-continent-2-episodio-173'
st, html = retry(get, ep)
csrf = re.search(r'name="csrf-token"\s+content="([^"]+)"', html)
tags = re.findall(r'<button[^>]*server-tab-btn[^>]*>', html)
rumb = None
for t in tags:
    vid = re.search(r'data-video-id="([^"]+)"', t)
    sidx = re.search(r'data-server-index="([^"]+)"', t)
    if not (vid and sidx): continue
    st2, body = post('https://seriesdonghua.com/api/player/get-server',
                     {'video_id': vid.group(1), 'server_index': sidx.group(1)}, refs=ep,
                     extra={'X-CSRF-TOKEN': csrf.group(1), 'X-Requested-With': 'XMLHttpRequest',
                            'Accept': 'application/json'})
    if st2 == 200:
        try:
            e = (json.loads(body).get('embed_url') or '').replace('\\/', '/')
        except Exception:
            e = ''
        if 'rumble.com' in e: rumb = e
    time.sleep(0.5)
print('rumble embed:', rumb)
# flujo nuevo de extractSdRumble
vid = re.search(r'rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)', rumb).group(1)
api = ('https://rumble.com/embedJS/u3/?request=video&v=' + vid +
       '&embed=' + urllib.parse.quote(rumb, safe=''))
UA_MP4 = r'"(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"'
st3, j = get(api, refs='https://rumble.com/')
mp4s = re.findall(UA_MP4, j.replace('\\/', '/'))
print(f'1) API HTTP {st3} mp4 {len(mp4s)}')
if not mp4s:
    get(rumb, refs='https://seriesdonghua.com/')
    st4, j2 = get(api, refs=rumb)
    mp4s = re.findall(UA_MP4, j2.replace('\\/', '/'))
    print(f'2) API warm-up HTTP {st4} mp4 {len(mp4s)}')
if not mp4s:
    st5, html2 = get(rumb, refs='https://seriesdonghua.com/')
    fixed = html2.replace('\\/', '/')
    hls = re.search(r'"hls":\{"url":"(https://rumble\.com/hls-vod/[^"]+)"', fixed)
    print(f'3) HTML fallback HTTP {st5} hls-vod: {bool(hls)}')
    if hls:
        st6, pl = get(hls.group(1), refs=rumb)
        segs = [l for l in pl.split('\n') if l.strip() and not l.startswith('#')]
        print(f'   playlist HTTP {st6} variantes {len(segs)} | empieza EXTM3U: {pl.startswith("#EXTM3U")}')
        for s in segs[:3]: print('    ', s[:120])
for h, u in mp4s[:2]:
    # validar un mp4 con Range
    req = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': 'https://rumble.com/',
                                             'Range': 'bytes=0-63'})
    try:
        r = opener.open(req, timeout=40)
        print(f'   {h}p HTTP {r.status} magic {r.read(16)[:12]}')
    except Exception as e:
        print(f'   {h}p ERR {type(e).__name__}')
