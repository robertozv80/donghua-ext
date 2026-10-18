# -*- coding: utf-8 -*-
"""v24.13 - Auditoria de servidores de TioDonghua.
3 fichas x 4 episodios -> doo_player_ajax nume 1..8 -> sonda por host ->
tally vivos/muertos por servidor para decidir depurar o reordenar.
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

def post(url, data, refs=None, to=30):
    h = {'User-Agent': UA, 'Content-Type': 'application/x-www-form-urlencoded'}
    if refs: h['Referer'] = refs
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
        time.sleep(2.5 * (i + 1))
    return st, b

TIOD = 'https://tiodonghua.lat'

def host_label(u):
    if 'rumble.com' in u: return 'DM(rumble)'
    if 'ok.ru' in u: return 'OK(ok.ru)'
    if 'terabox' in u: return 'TB(terabox)'
    if 'embedme' in u or 'fmoonembed' in u: return 'Filemon'
    if 'streamtape' in u or 'strcloud' in u: return 'ST(streamtape)'
    if 'd000d' in u: return 'DS(d000d)'
    if 'voe.' in u or 'vidhide' in u: return 'VOE(vidhide)'
    if 'asnwish' in u: return 'SW(asnwish)'
    m = re.match(r'https?://([^/]+)', u)
    return 'other:' + (m.group(1) if m else u[:40])

UA_MAP = r'"(\d{3,4})":\["(https://[a-z0-9.]*rumble\.cloud/video/[A-Za-z0-9/._-]+\.mp4)"'

def probe(u, refs):
    if 'rumble.com' in u:
        vid = re.search(r'rumble\.com/(?:embed/)?(v[a-zA-Z0-9]+)', u)
        if not vid: return False, 'sin vid'
        api = ('https://rumble.com/embedJS/u3/?request=video&v=' + vid.group(1) +
               '&embed=' + urllib.parse.quote(u, safe=''))
        st, j = get(api, refs='https://rumble.com/')
        if st != 200:
            get(u, refs=refs)
            st, j = get(api, refs=u)
        n = len(re.findall(UA_MAP, j.replace('\\/', '/')))
        return n > 0, f'API HTTP {st} calidades {n}'
    st, body = get(u, refs=refs)
    if st != 200:
        return False, f'HTTP {st} len {len(body)}'
    m3u8 = len(re.findall(r'https?://[^"\'\s\\]+\.m3u8', body))
    mp4 = len(re.findall(r'https?://[^"\'\s\\]+\.mp4', body))
    low = body.lower()
    if 'terabox' in u:
        return bool(m3u8 or mp4), f'player propio len {len(body)} m3u8 {m3u8} mp4 {mp4}'
    if 'ok.ru' in u:
        ok = 'data-options' in body
        return ok, f'len {len(body)} data-options {ok}'
    if 'asnwish' in u:
        dead = 'no longer available' in low or len(body) < 1500
        return (m3u8 > 0 and not dead), f'len {len(body)} m3u8 {m3u8} deadflag {dead}'
    if 'embedme' in u or 'fmoonembed' in u:
        trap = 'qyvir' in low or len(body) < 3000
        return (m3u8 > 0 and not trap), f'len {len(body)} m3u8 {m3u8} trap {trap}'
    if 'streamtape' in u:
        return ('get_video' in low or 'robotlink' in low), f'len {len(body)}'
    if 'voe.' in u or 'vidhide' in u:
        return m3u8 > 0, f'len {len(body)} m3u8 {m3u8}'
    if 'd000d' in u:
        return (m3u8 + mp4) > 0, f'len {len(body)} m3u8 {m3u8} mp4 {mp4}'
    return (m3u8 + mp4) > 0, f'len {len(body)} m3u8 {m3u8} mp4 {mp4}'

tally = {}
def tally_add(lbl, viva):
    t = tally.setdefault(lbl, [0, 0])
    t[0 if viva else 1] += 1

# fichas: Star Divine Arts + 2 del home
fichas = [TIOD + '/donghua/the-secrets-of-star-divine-arts-sub-espanol/']
st, home = retry(get, TIOD + '/')
others = list(dict.fromkeys(re.findall(r"href=['\"](https://tiodonghua\.lat/donghua/[^'\"]+)", home)))
others = [u for u in others if 'star-divine-arts' not in u]
fichas += others[:2]
print('fichas muestra:')
for f in fichas: print('  ', f)

for ficha in fichas:
    st, fh = retry(get, ficha)
    if st != 200:
        print('ficha HTTP', st, ficha); continue
    eps = list(dict.fromkeys(re.findall(r"href=['\"](https://tiodonghua\.lat/episodios/[^'\"]+)", fh)))
    if not eps:
        print('sin episodios en', ficha); continue
    idxs = sorted(set([0, len(eps) // 3, (2 * len(eps)) // 3, len(eps) - 1]))
    print('=' * 76)
    print(ficha.split('/donghua/')[1], '->', len(eps), 'eps, muestra', idxs)
    for i in idxs:
        ep = eps[i]
        print('-' * 76)
        print('EP', ep.rsplit('/', 2)[-2])
        st, eh = retry(get, ep)
        if st != 200:
            print('  HTTP', st); continue
        mp = re.search(r'postid-(\d+)', eh)
        if not mp:
            print('  sin postid'); continue
        postid = mp.group(1)
        for nume in range(1, 9):
            st2, body = post(TIOD + '/wp-admin/admin-ajax.php',
                             {'action': 'doo_player_ajax', 'post': postid,
                              'nume': str(nume), 'type': 'tv'}, refs=ep)
            emb = ''
            if st2 == 200:
                m = re.search(r'"embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"', body)
                if m: emb = m.group(1).replace('\\"', '"').replace('\\/', '/')
            if not emb:
                tally_add('(vacio)', False)
                print(f'  nume {nume} (vacio)')
                time.sleep(1)
                continue
            lbl = host_label(emb)
            try:
                viva, nota = probe(emb, ep)
            except Exception as e:
                viva, nota = False, f'EXC {type(e).__name__}'
            tally_add(lbl, viva)
            print(f'  nume {nume} {lbl:16s} viva={str(viva):5s} {nota}')
            time.sleep(1)
        time.sleep(4)

print('=' * 76)
print('TALLY FINAL (vivas / muertas):')
for lbl in sorted(tally, key=lambda k: -tally[k][0]):
    v, d = tally[lbl]
    print(f'  {lbl:20s} {v:3d} / {d:3d}')
