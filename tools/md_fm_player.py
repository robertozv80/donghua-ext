# -*- coding: utf-8 -*-
"""Sonda v24.14i - player real n1mwq.org desde embed_frame_url."""
import http.cookiejar, urllib.request, gzip, io, sys, time, re, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def _read(r):
    raw = r.read()
    if r.headers.get('Content-Encoding') == 'gzip':
        raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
    return raw.decode('utf-8', 'replace')

def get(url, refs=None, origin=None, to=40, extra=None):
    h = {'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'es-ES,es;q=0.8,en;q=0.5'}
    if refs: h['Referer'] = refs
    if origin: h['Origin'] = origin
    if extra: h.update(extra)
    try:
        r = opener.open(urllib.request.Request(url, headers=h), timeout=to)
        return r.status, _read(r), r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace'), url
    except Exception as e:
        return -1, f'{type(e).__name__}: {e}', url

def retry(fn, *a, **k):
    st, b, u = -1, '', ''
    for i in range(3):
        st, b, u = fn(*a, **k)
        if st == 200: return st, b, u
        time.sleep(2.5 * (i + 1))
    return st, b, u

# paso 1: details
st, b, _ = get('https://bysekoze.com/api/videos/rgcgmsu5j63y/embed/details', origin='https://www.mundodonghua.com')
frame = json.loads(b).get('embed_frame_url')
print('embed_frame_url:', frame)

# paso 2: player page con distintos contextos
for name, refs, orig in [('sin ref', None, None),
                         ('ref bysekoze', 'https://bysekoze.com/e/rgcgmsu5j63y', 'https://bysekoze.com'),
                         ('ref mundodonghua', 'https://www.mundodonghua.com/', 'https://www.mundodonghua.com')]:
    st2, html, final = get(frame, refs=refs, origin=orig)
    print(f'--- player [{name}] HTTP {st2} len {len(html)} final {final}')
    if st2 == 200:
        urls = re.findall(r'https?://[^\s"\'<>\\]+', html)
        vids = [u for u in urls if '.m3u8' in u or '.mp4' in u]
        print('    urls video:', list(dict.fromkeys(vids))[:6])
        # packed?
        if 'eval(function(p,a,c,k,e' in html:
            print('    contiene packed JS')
            open('tools/md_player_packed.html', 'w', encoding='utf-8').write(html)
        # jwplayer / videojs setup
        for kw in ['jwplayer', 'videojs', 'file', 'sources', 'playlist', 'player.php']:
            if kw in html.lower():
                ix = html.lower().find(kw)
                print(f'    [{kw}] ...{html[max(0,ix-60):ix+140]}...')
        if st2 == 200 and len(html) < 20000:
            print('    HTML completo:')
            print(html[:3000])
        break
    time.sleep(2)
