# -*- coding: utf-8 -*-
"""v24.13 - Auditoria refinada de los casos dudosos de tio_audit.py.
Rumble: API .mp4 + HTML hls-vod (los .tar solo salen por hls-vod).
Dailymotion: metadata API (como extractTdDailymotion).
Ok.ru: data-options | hlsManifestUrl | ondemandHls.
"""
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

def post(url, data, refs=None, to=30):
    h = {'User-Agent': UA, 'Content-Type': 'application/x-www-form-urlencoded'}
    if refs: h['Referer'] = refs
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

TIOD = 'https://tiodonghua.lat'
EPS = [
    TIOD + '/episodios/the-secrets-of-star-divine-arts-episodio-1-sub-espanol/',
    TIOD + '/episodios/the-secrets-of-star-divine-arts-episodio-27-sub-espanol/',
    TIOD + '/episodios/the-secrets-of-star-divine-arts-episodio-40-sub-espanol/',
    TIOD + '/episodios/martial-god-asura-episodio-1-sub-espanol/',
    TIOD + '/episodios/dios-marcial-asura-2-episodio-6-sub-espanol/',
    TIOD + '/episodios/dios-marcial-asura-2-episodio-16-sub-espanol/',
    TIOD + '/episodios/legend-of-immortals-episodio-1-sub-espanol/',
    TIOD + '/episodios/legend-of-immortals-7-episodio-12-sub-espanol/',
]

UA_MAP = r'"(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.(?:mp4|tar))"'

def probe(u, refs):
    """Devuelve (viva, nota) con heuristicas afinadas."""
    if 'rumble.com' in u:
        vid = re.search(r'rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)', u)
        if not vid: return False, 'sin vid'
        api = ('https://rumble.com/embedJS/u3/?request=video&v=' + vid.group(1) +
               '&embed=' + urllib.parse.quote(u, safe=''))
        st, j = get(api, refs='https://rumble.com/')
        if st != 200:
            get(u, refs=refs)
            st, j = get(api, refs=u)
        fixed = j.replace('\\/', '/')
        mp4 = len(re.findall(r'"(\d{3,4})":\["https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4', fixed))
        tar = len(re.findall(r'"(\d{3,4})":\["https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.tar', fixed))
        hls = 0
        if mp4 == 0:
            st2, html = get(u, refs=refs)
            hls = len(re.findall(r'"hls":\{"url":"https://rumble\.com/hls-vod/', html.replace('\\/', '/')))
        return (mp4 > 0 or hls > 0), f'API {st} mp4 {mp4} tar {tar} hls-vod {hls}'
    if 'dailymotion.com' in u or 'dai.ly' in u:
        vid = re.search(r'(?:dailymotion\.com|dai\.ly)/(?:embed/)?(?:video/|player(?:/[a-z0-9]+)?\.html\?video=)([a-zA-Z0-9]+)', u)
        if not vid: return False, 'sin id'
        can = 'https://www.dailymotion.com/embed/video/' + vid.group(1)
        get(can, refs=refs)
        st, j = get('https://www.dailymotion.com/player/metadata/video/' + vid.group(1), refs=can)
        if st != 200:
            return False, f'metadata HTTP {st}'
        n = len(re.findall(r'"url":"(https?://[^"]+\.m3u8[^"]*)"', j.replace('\\/', '/')))
        return n > 0, f'metadata HTTP {st} m3u8 {n}'
    if 'drive.google.com' in u:
        return True, 'transform directo (verificado v24.3)'
    st, body = get(u, refs=refs)
    if st != 200:
        return False, f'HTTP {st} len {len(body)}'
    m3u8 = len(re.findall(r'https?://[^"\'\s\\]+\.m3u8', body))
    mp4 = len(re.findall(r'https?://[^"\'\s\\]+\.mp4', body))
    low = body.lower()
    if 'ok.ru' in u:
        ok = 'data-options' in body
        hls = 'hlsManifestUrl' in body or 'ondemandHls' in body
        return (ok or hls), f'len {len(body)} data-options {ok} hls {hls}'
    if 'terabox' in u:
        return bool(m3u8 or mp4), f'player propio len {len(body)} m3u8 {m3u8} mp4 {mp4}'
    if 'asnwish' in u or 'obeywish' in u:
        dead = 'no longer available' in low or len(body) < 1500
        return (m3u8 > 0 and not dead), f'len {len(body)} m3u8 {m3u8} dead {dead}'
    if 'filemoon' in u or 'embedme' in u or 'fmoonembed' in u:
        trap = 'qyvir' in low or len(body) < 3000
        return (m3u8 > 0 and not trap), f'len {len(body)} m3u8 {m3u8} trap {trap}'
    if 'streamtape' in u:
        return ('get_video' in low or 'robotlink' in low), f'len {len(body)}'
    if 'voe.' in u or 'vidhide' in u:
        return m3u8 > 0, f'len {len(body)} m3u8 {m3u8}'
    if 'd000d' in u:
        return (m3u8 + mp4) > 0, f'len {len(body)} m3u8 {m3u8} mp4 {mp4}'
    if 'tioplayer' in u or 'playerp2p' in u or 'modagamers' in u or 'player.tiodonghua' in u:
        return (m3u8 + mp4) > 0, f'agregador/player len {len(body)} m3u8 {m3u8} mp4 {mp4}'
    return (m3u8 + mp4) > 0, f'len {len(body)} m3u8 {m3u8} mp4 {mp4}'

def host_label(u):
    if 'rumble.com' in u: return 'DM(rumble)'
    if 'dailymotion.com' in u or 'dai.ly' in u: return 'Dailymotion'
    if 'ok.ru' in u: return 'OK(ok.ru)'
    if 'terabox' in u: return 'TB(terabox)'
    if 'drive.google.com' in u: return 'Drive'
    if 'embedme' in u or 'fmoonembed' in u or 'filemoon' in u: return 'Filemon'
    if 'streamtape' in u or 'strcloud' in u: return 'ST(streamtape)'
    if 'd000d' in u: return 'DS(d000d)'
    if 'voe.' in u or 'vidhide' in u: return 'VOE(vidhide)'
    if 'asnwish' in u or 'obeywish' in u: return 'SW(asnwish)'
    if 'tioplayer' in u: return 'MultiPlayer(tio)'
    if 'player.tiodonghua' in u: return 'MultiPlayer(tdh)'
    m = re.match(r'https?://([^/]+)', u)
    return 'other:' + (m.group(1) if m else u[:30])

tally = {}
for ep in EPS:
    st, eh = retry(get, ep)
    if st != 200:
        print('EP HTTP', st, ep); continue
    mp = re.search(r'postid-(\d+)', eh)
    if not mp:
        print('sin postid', ep); continue
    print('=' * 76)
    print(ep.rsplit('/', 2)[-2])
    for nume in range(1, 9):
        st2, body = post(TIOD + '/wp-admin/admin-ajax.php',
                         {'action': 'doo_player_ajax', 'post': mp.group(1),
                          'nume': str(nume), 'type': 'tv'}, refs=ep)
        emb = ''
        if st2 == 200:
            m = re.search(r'"embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
            if m: emb = m.group(1).replace('\\"', '"').replace('\\/', '/')
        # normalizar iframe HTML como el provider
        if emb and '<iframe' in emb:
            msrc = re.search(r'src\s*=\s*[\'"]([^\'"]+)[\'"]', emb)
            emb = msrc.group(1) if msrc else ''
        if not emb:
            time.sleep(0.8); continue
        lbl = host_label(emb)
        try:
            viva, nota = probe(emb, ep)
        except Exception as e:
            viva, nota = False, f'EXC {type(e).__name__}'
        t = tally.setdefault(lbl, [0, 0]); t[0 if viva else 1] += 1
        print(f'  nume {nume} {lbl:18s} viva={str(viva):5s} {nota}')
        time.sleep(0.8)
    time.sleep(3)

print('=' * 76)
print('TALLY REFINADO (vivas / muertas):')
for lbl in sorted(tally, key=lambda k: -(tally[k][0] + tally[k][1])):
    v, d = tally[lbl]
    print(f'  {lbl:20s} {v:3d} / {d:3d}')
